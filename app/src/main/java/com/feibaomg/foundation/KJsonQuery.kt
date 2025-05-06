package com.feibaomg.foundation

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.security.InvalidParameterException
import kotlin.collections.get
import kotlin.text.iterator


private const val TAG = "KJsonQuery"

class KJsonQuery {
    // dedicated cache for array fields
    @VisibleForTesting
    private val arrayFieldsCache = mutableMapOf<String, List<Any?>>()

    lateinit var jsonFile: File

    // mapped byte buffer for efficient reading
    private lateinit var fileChannel: FileChannel
    private lateinit var mappedByteBuffer: MappedByteBuffer

    companion object {
        val sInstanceMap = mutableMapOf<String, KJsonQuery>()

        fun getInstance(filepath: String): KJsonQuery {
            synchronized(sInstanceMap) {
                if (sInstanceMap[File(filepath).absolutePath] == null) {
                    sInstanceMap[File(filepath).absolutePath] = KJsonQuery(filepath)
                }
            }
            return sInstanceMap[File(filepath).absolutePath]!!
        }

        fun getInstance(file: File): KJsonQuery {
            synchronized(sInstanceMap) {
                if (sInstanceMap[file.absolutePath] == null) {
                    sInstanceMap[file.absolutePath] = KJsonQuery(file)
                }
            }
            return sInstanceMap[file.absolutePath]!!
        }
    }

    private constructor(filepath: String) {
        mapJsonFileToByteBuffer(File(filepath))
    }

    private constructor(file: File) {
        jsonFile = file
        mapJsonFileToByteBuffer(file)
    }


    /**
     * Queries a JSON file using a JSONPath expression and returns matching elements.
     *
     * This function performs streaming parsing of JSON data to search and return all matching elements.
     * If the query is filtering an already cached array (e.g., "$.users[@.id=1]"),
     * it will use the cached array instead of reading from the file again.
     *
     * Note: Using limit=1 parameter is recommended for better performance (returns immediately after finding one match).
     *
     * @param jsonPath The JSONPath expression to query the JSON data. Defaults to "$",
     *                 which represents the root node of the JSON document.
     * @param limit The maximum number of results to return. If set to a positive number,
     *              only that many results will be returned. If set to -1 (default),
     *              all matching results will be returned.
     * @param filter An optional function that takes a JSON element and returns a boolean,
     *               allowing for custom filtering of results beyond what JSONPath expressions can do.
     * @return A list containing all JSON elements matching the query. Returns an empty list
     *         if no matches are found or if an error occurs during processing.
     */
    fun query(jsonPath: String = "$", limit: Int = -1, filter: ((Any?) -> Boolean)? = null): List<Any?> {
        // Check if this is a filter on a cached array
        val result = queryInCachedArray(jsonPath, limit)
        if (result != null) {
            return result
        }

        var jsonReader: JsonReader? = null
        try {
            if (mappedByteBuffer.capacity() == 0) {
                mapJsonFileToByteBuffer(jsonFile)
                if (mappedByteBuffer.capacity() == 0) {
                    return emptyList()
                }
            }
            // Create a Gson JsonReader for streaming parsing
            jsonReader = createJsonReader(mappedByteBuffer)

            // Query JSON with the provided JSONPath and custom filter
            var results = queryJson(jsonReader, jsonPath, limit)

            mappedByteBuffer.rewind()

            if (results.size == 1 && results[0] is List<*>) {
                results = results[0] as List<Any?>
            }

            if (filter != null) {
                results = results.filter(filter)
            }

            return if (limit > 0) results.take(limit) else results

        } catch (e: Exception) {
            Log.e(TAG, "Error querying JSON: ", e)
            return emptyList()
        } finally {
            try {
                jsonReader?.close()
            } catch (_: IOException) {
            }
        }
    }

    /**
     * 在缓存的数组中执行查询操作
     *
     * 此方法首先检查完整的jsonPath是否已被缓存，如果是则直接返回缓存结果。
     * 否则，尝试从jsonPath中提取数组路径和过滤表达式，例如从"$.users[?(@.id=1)]"
     * 提取出"$.users"和"@.id=1"。
     *
     * 如果提取成功且数组路径已被缓存，则对缓存的数组应用过滤表达式，
     * 而不是重新从文件中读取整个JSON。这大大提高了查询性能，
     * 特别是对大型JSON文件的重复查询。
     *
     * @param jsonPath 要查询的JSONPath表达式
     * @param limit 返回结果的最大数量，-1表示不限制
     * @return 匹配的结果列表，如果无法在缓存中执行查询则返回null
     */
    private fun queryInCachedArray(jsonPath: String, limit: Int = -1): List<Any?>? {
        val arrayPathAndFilter = extractArrayPathAndFilter(jsonPath)
        if (arrayPathAndFilter == null) {
            //如果查询不包含过滤条件，直接返回缓存中对应的数据（可能为null）
            return arrayFieldsCache[jsonPath]
        }

        val (arrayPath, filterExpression) = arrayPathAndFilter

        val cachedResult = arrayFieldsCache[arrayPath]
        if (cachedResult != null) {
            return cachedResult
        }

        if (!arrayFieldsCache.containsKey(arrayPath)) {
            return null
        }

        Log.d(TAG, "Using cached array for filtered query: $arrayPath")
        val cachedArray = arrayFieldsCache[arrayPath]!!
        return applyFilterToArray(cachedArray, filterExpression, limit)
    }

    /**
     * 从JSONPath表达式中提取数组路径和过滤表达式
     *
     * 此方法解析包含过滤条件的JSONPath表达式，将其分解为基本数组路径和过滤表达式。
     * 例如，对于"$.users[?(@.id=1)]"，会返回("$.users", "@.id=1")。
     *
     * 该方法能够处理复杂的嵌套表达式，如"$.users[?((@.id==1&&@.name=="John")||@.role=="admin")]"，
     * 通过跟踪括号的嵌套深度来正确识别过滤表达式的边界。
     *
     * @param jsonPath 要解析的JSONPath表达式
     * @return 包含数组路径和过滤表达式的键值对，如果表达式不包含过滤条件则返回null
     */
    private fun extractArrayPathAndFilter(jsonPath: String): Pair<String, String>? {
        // Find the position of the filter start
        val filterStartPos = jsonPath.indexOf("[?")
        if (filterStartPos == -1) return null

        // Extract the array path
        val arrayPath = jsonPath.substring(0, filterStartPos)

        // Now extract the filter expression by finding the matching closing bracket
        var depth = 0
        var filterEndPos = -1

        for (i in filterStartPos until jsonPath.length) {
            when (jsonPath[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        filterEndPos = i
                        break
                    }
                }
            }
        }

        if (filterEndPos == -1) {
            Log.w(TAG, "Could not find matching closing bracket for filter in: $jsonPath")
            return null
        }

        // Extract the filter expression including the [? and ]
        val fullFilterExpr = jsonPath.substring(filterStartPos, filterEndPos + 1)
        // Remove the [? and ] to get just the filter content
        val filterContent = fullFilterExpr.substring(2, fullFilterExpr.length - 1)

        Log.d(TAG, "Extracted array path: $arrayPath, filter: $filterContent")
        return Pair(arrayPath, filterContent)
    }

    /**
     * 将过滤表达式应用于缓存的数组
     *
     * 此方法接收一个数组和过滤表达式，解析过滤表达式并将其应用于数组中的每个元素。
     * 它首先将过滤表达式解析为结构化的过滤器对象，然后遍历数组中的每个元素，
     * 检查每个元素是否满足过滤条件。满足条件的元素会被添加到结果列表中。
     *
     * 如果指定了limit参数，则在结果数量达到限制时会提前停止处理，提高查询效率。
     *
     * @param array 要过滤的数组
     * @param filterExpression 过滤表达式字符串，例如"@.id==1"
     * @param limit 返回结果的最大数量，-1表示不限制
     * @return 满足过滤条件的元素列表
     */
    private fun applyFilterToArray(array: List<Any?>, filterExpression: String, limit: Int): List<Any?> {
        val filter = parseFilter(filterExpression)
        val results = mutableListOf<Any?>()
        Log.d(TAG, "applyFilterToArray: $filter")
        for (item in array) {
            if (item is Map<*, *> && matchesFilter(item as Map<*, *>, filter)) {
                results.add(item)
                if (limit > 0 && results.size >= limit) {
                    break
                }
            }
        }

        return results
    }

    /**
     * 缓存JSON数组字段以加速后续的数组内元素的反复查询
     *
     * 此方法查询指定路径的JSON数组并将其缓存在内存中，以便后续对该数组的过滤操作
     * 可以直接在内存中执行，而无需重新解析整个JSON文件。这显著提高了对大型JSON文件
     * 中数组的重复查询性能。
     *
     * @param arrayPath 数组的JSONPath路径（例如"$.users"）
     * @param cacheKey 可选的缓存键名，如果不提供则默认使用arrayPath作为键
     * @return 缓存的数组，如果路径不指向数组则返回null
     */
    fun cacheArrayField(arrayPath: String, cacheKey: String = arrayPath): List<Any?>? {
        // Check if already cached
        if (arrayFieldsCache.containsKey(arrayPath)) {
            return arrayFieldsCache[arrayPath]
        }

        // Query the array
        val result = query(arrayPath)
        // Only cache if it's actually an array (list)
        if (result.size == 1 && result[0] is List<*>) {
            val arrayResult = result[0] as List<*>
            arrayFieldsCache[cacheKey] = arrayResult
            Log.d(TAG, "Cached array field: $arrayPath with ${arrayResult.size} items")
            return arrayResult
        } else if (result.isNotEmpty()) {
            // If the result itself is a list of objects, cache it directly
            arrayFieldsCache[cacheKey] = result
            Log.d(TAG, "Cached array field: $arrayPath with ${result.size} items")
            return result
        }

        Log.d(TAG, "Path does not point to an array: $arrayPath")
        return null
    }

    /**
     * Checks if an array field is cached
     *
     * @param arrayPath The JSONPath to check
     * @return true if the array is cached
     */
    fun isArrayFieldCached(arrayPath: String): Boolean {
        return arrayFieldsCache.containsKey(arrayPath)
    }

    /**
     * Invalidates a cached array field
     *
     * @param arrayPath The JSONPath to invalidate
     */
    fun invalidateArrayCache(arrayPath: String) {
        arrayFieldsCache.remove(arrayPath)
    }

    /**
     * Clears all cached array fields
     */
    fun clearArrayCache() {
        arrayFieldsCache.clear()
    }

    fun recreateFileBuffer() {
        if (::jsonFile.isInitialized) {
            mapJsonFileToByteBuffer(jsonFile)
        }
    }

    fun release() {
        clearArrayCache()
        releaseFileBuffer()
    }

    fun releaseFileBuffer() {
        if (::mappedByteBuffer.isInitialized) {
            try {
                mappedByteBuffer.clear()
            } catch (_: IOException) {
            }
        }
        if (::fileChannel.isInitialized) {
            try {
                fileChannel.close()
            } catch (_: IOException) {
            }
        }
    }

    fun invalidateCache(jsonPath: String) {
        arrayFieldsCache.remove(jsonPath)
    }

    /**
     *  Rewind the buffer to the beginning for next gson reader reading again
     */
    fun rewindBuffer() {
        if (::mappedByteBuffer.isInitialized) {
            mappedByteBuffer.rewind()
        }
    }

    private fun queryJson(reader: JsonReader, jsonPath: String, limit: Int = -1): List<Any?> {
        val pathSegments = parseJsonPath(jsonPath)
        Log.d(TAG, "jsonpath segments: $pathSegments")
        val results = evaluatePath(reader, pathSegments, 0, limit = limit)
        Log.d(TAG, "\njsonPath=$jsonPath\nresults=${if (results.size > 10) "${results.size} items" else results}")
        return results
    }

    /**
     * 评估JSONPath路径并返回匹配的结果
     *
     * 此方法是JSONPath查询的核心实现，它递归地处理JSONPath路径段并在JSON数据中查找匹配项。
     * 方法通过深度优先遍历的方式，根据当前路径段的类型（属性、数组索引、过滤器等）
     * 在JSON结构中导航，并收集所有匹配的结果。
     *
     * 支持以下功能：
     * - 属性访问（如$.store.book）
     * - 数组索引访问（如$.store.book[0]）
     * - 通配符（如$.store.*）
     * - 数组过滤（如$.store.book[?(@.price<10)]）
     * - 结果数量限制（提高性能）
     *
     * @param reader JSON读取器，指向当前解析位置
     * @param pathSegments 解析后的JSONPath路径段列表
     * @param index 当前处理的路径段索引
     * @param customFilter 可选的自定义过滤函数
     * @param limit 返回结果的最大数量，-1表示不限制
     * @return 匹配路径的所有JSON元素列表
     */
    private fun evaluatePath(
        reader: JsonReader,
        pathSegments: List<PathSegment>,
        index: Int,
        customFilter: ((Any?) -> Boolean)? = null,
        limit: Int = -1
    ): List<Any?> {
        if (index >= pathSegments.size) {
            val value = readValue(reader)
            return if (customFilter?.invoke(value) != false) {
                listOf(value)
            } else {
                emptyList()
            }
        }

        val currentSegment = pathSegments[index]
        val results = mutableListOf<Any?>()

        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()

                when (currentSegment) {
                    is PathSegment.Property -> {
                        while (reader.hasNext() && (limit <= 0 || results.size < limit)) {
                            val name = reader.nextName()
                            if (name == currentSegment.name) {
                                results.addAll(evaluatePath(reader, pathSegments, index + 1, customFilter, limit - results.size))
                            } else {
                                reader.skipValue()
                            }
                        }
                        // Skip remaining properties if we've reached the limit
                        while (reader.hasNext() && limit > 0 && results.size >= limit) {
                            reader.nextName()
                            reader.skipValue()
                        }
                    }

                    is PathSegment.AllElements -> {
                        while (reader.hasNext() && (limit <= 0 || results.size < limit)) {
                            reader.nextName()
                            results.addAll(evaluatePath(reader, pathSegments, index + 1, customFilter, limit - results.size))
                        }
                        // Skip remaining properties if we've reached the limit
                        while (reader.hasNext() && limit > 0 && results.size >= limit) {
                            reader.nextName()
                            reader.skipValue()
                        }
                    }

                    else -> {
                        // Skip this object as it doesn't match our path
                        while (reader.hasNext()) {
                            reader.nextName()
                            reader.skipValue()
                        }
                    }
                }

                reader.endObject()
            }

            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var arrayIndex = 0

                when (currentSegment) {
                    is PathSegment.ArrayIndex -> {
                        while (reader.hasNext()) {
                            if (arrayIndex == currentSegment.index) {
                                results.addAll(evaluatePath(reader, pathSegments, index + 1, customFilter, limit))
                            } else {
                                reader.skipValue()
                            }
                            arrayIndex++
                        }
                    }

                    is PathSegment.AllElements -> {
                        while (reader.hasNext() && (limit <= 0 || results.size < limit)) {
                            results.addAll(evaluatePath(reader, pathSegments, index + 1, customFilter, limit - results.size))
                            arrayIndex++
                        }
                        // Skip remaining elements if we've reached the limit
                        while (reader.hasNext() && limit > 0 && results.size >= limit) {
                            reader.skipValue()
                            arrayIndex++
                        }
                    }

                    is PathSegment.Filter -> {
                        while (reader.hasNext() && (limit <= 0 || results.size < limit)) {
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                // We need to check if this object matches the filter
                                val objectValue = readObject(reader)
                                if (objectValue is Map<*, *> && matchesFilter(objectValue, currentSegment)) {
                                    // If it matches, we need to evaluate the rest of the path on this object
                                    val tempReader = createTempJsonReader(objectValue)
                                    results.addAll(evaluatePath(tempReader, pathSegments, index + 1, customFilter, limit - results.size))
                                }
                            } else {
                                reader.skipValue()
                            }
                            arrayIndex++
                        }
                        // Skip remaining elements if we've reached the limit
                        while (reader.hasNext() && limit > 0 && results.size >= limit) {
                            reader.skipValue()
                            arrayIndex++
                        }
                    }

                    else -> {
                        // Skip this array as it doesn't match our path
                        while (reader.hasNext()) {
                            reader.skipValue()
                            arrayIndex++
                        }
                    }
                }

                reader.endArray()
            }

            else -> {
                // We've reached a primitive value, but we still have path segments to process
                reader.skipValue()
            }
        }

        return results
    }

    /**
     * 解析JSONPath表达式为路径段列表
     *
     * 此方法将JSONPath字符串（如"$.store.book[0].title"）解析为结构化的路径段列表，
     * 每个路径段代表JSONPath中的一个组件（属性名、数组索引、过滤器等）。
     *
     * 方法能够处理复杂的JSONPath表达式，包括：
     * - 属性访问（如$.store.book）
     * - 数组索引（如$[0]或$.books[1]）
     * - 通配符（如$.store.*）
     * - 过滤表达式（如$.store.book[?(@.price<10)]）
     * - 嵌套过滤条件（如$.book[?((@.price>10&&@.category=="fiction")||@.author=="Tolkien")]）
     *
     * @param jsonPath 要解析的JSONPath表达式字符串
     * @return 解析后的PathSegment对象列表，表示JSONPath的各个组成部分
     */
    //"$.IniTopicDialog.data[?(@.roleId==20001&@.dialogueId==439)]"
    private fun parseJsonPath(jsonPath: String): List<PathSegment> {
        val segments = mutableListOf<PathSegment>()

        // Remove the root symbol if present
        val path = if (jsonPath.startsWith("$")) jsonPath.substring(1) else jsonPath

        // Split by dots, but handle array notation properly
        var currentSegment = ""
        //in []
        var inBracket = false
        //in ()
        var inFilter = false

        for (char in path) {
            when {
                char == '.' && !inBracket && !inFilter -> {
                    if (currentSegment.isNotEmpty()) {
                        segments.add(parsePathSegment(currentSegment))
                        currentSegment = ""
                    }
                }

                char == '[' && !inBracket && !inFilter -> {
                    if (currentSegment.isNotEmpty()) {
                        segments.add(PathSegment.Property(currentSegment))
                        currentSegment = ""
                    }
                    inBracket = true
                    currentSegment += char
                }

                char == ']' && inBracket -> {
                    currentSegment += char
                    inBracket = false

                    if (!inFilter) {
                        Log.d(TAG, "Current segment: $currentSegment")
                        segments.add(parsePathSegment(currentSegment))
                        currentSegment = ""
                    }
                }

                char == '(' && inBracket -> {
                    currentSegment += char
                    inFilter = true
                }

                char == ')' && inFilter -> {
                    currentSegment += char
                    inFilter = false
                }

                else -> currentSegment += char
            }

        }

        if (currentSegment.isNotEmpty()) {
            segments.add(parsePathSegment(currentSegment))
        }

        return segments
    }

    private fun parsePathSegment(segment: String): PathSegment {

        return when {
            // [?@(fixEventID==1)]
            segment.startsWith("[") && segment.endsWith("]") -> {
                val content = segment.substring(1, segment.length - 1)
                when {
                    content == "*" -> PathSegment.AllElements
                    content.toIntOrNull() != null -> PathSegment.ArrayIndex(content.toInt())
                    //?@(fixEventID==1)
                    content.startsWith("?") -> {
                        val filter = content.substring(1).trim()
                        parseFilter(filter)
                    }

                    else -> PathSegment.Property(content.trim('\'', '"'))
                }
            }

            else -> PathSegment.Property(segment)
        }
    }

    private fun parseFilter(filter: String): PathSegment.Filter {
        // Handle expressions like
        // (@.price < 10 && @.category == "book")
        // ((@.number=="0123-4567-8910"&&@.type=="home2")||@.type=="home")
        val expression = filter.trim()
            .removePrefix("(").removeSuffix(")")
            .trim()

        return parseComplexExpression(expression)
    }

    /**
     * 解析复杂表达式，支持嵌套括号和逻辑运算符
     * 例如: (@.category=="数学"&&@.price>50)||(@.category=="历史"&&@.price<10)
     */
    private fun parseComplexExpression(expression: String): PathSegment.Filter {
        // 处理表达式规范化
        val normalizedExpression = normalizeExpression(expression)

        // 检查顶层OR运算符
        if (hasTopLevelOperator(normalizedExpression, "||")) {
            val parts = splitByTopLevelOperator(normalizedExpression, "||")
            val conditions = mutableListOf<PathSegment.Filter.Condition>()
            val subFilters = mutableListOf<PathSegment.Filter>()

            for (part in parts) {
                // 每个部分可能是复杂表达式或简单条件
                val subFilter = parseComplexExpression(part.trim())
                subFilters.add(subFilter)
            }

            // 创建OR过滤器，包含所有子过滤器
            return PathSegment.Filter(emptyList(), "||", subFilters)
        }

        // 检查顶层AND运算符
        if (hasTopLevelOperator(normalizedExpression, "&&")) {
            val parts = splitByTopLevelOperator(normalizedExpression, "&&")
            val conditions = mutableListOf<PathSegment.Filter.Condition>()
            val subFilters = mutableListOf<PathSegment.Filter>()

            for (part in parts) {
                // 每个部分可能是复杂表达式或简单条件
                val subFilter = parseComplexExpression(part.trim())
                subFilters.add(subFilter)
            }

            // 创建AND过滤器，包含所有子过滤器
            return PathSegment.Filter(emptyList(), "&&", subFilters)
        }

        // 如果表达式被括号包围，递归解析内部表达式
        if (normalizedExpression.startsWith("(") && normalizedExpression.endsWith(")")) {
            return parseComplexExpression(normalizedExpression.substring(1, normalizedExpression.length - 1).trim())
        }

        // 处理简单条件
        val condition = parseCondition(normalizedExpression)
        return if (condition != null) {
            PathSegment.Filter(listOf(condition), "&&")
        } else {
            Log.w(TAG, "Failed to parse condition: $normalizedExpression")
            PathSegment.Filter(emptyList())
        }
    }

    /**
     * 规范化表达式，处理嵌套括号
     */
    private fun normalizeExpression(expression: String): String {
        // 检查括号是否平衡
        var depth = 0
        for (char in expression) {
            when (char) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth < 0) {
                        Log.w(TAG, "不平衡的括号在表达式中: $expression")
                        return expression
                    }
                }
            }
        }

        if (depth > 0) {
            Log.w(TAG, "不平衡的括号在表达式中: $expression")
            return expression
        }

        // 处理表达式两端可能有的多余括号
        var normalized = expression.trim()
        while (normalized.startsWith("(") && normalized.endsWith(")")) {
            // 检查这对括号是否是匹配的外层括号
            var innerDepth = 0
            var isOuterPair = true

            for (i in 1 until normalized.length - 1) {
                when (normalized[i]) {
                    '(' -> innerDepth++
                    ')' -> {
                        innerDepth--
                        if (innerDepth < 0) {
                            isOuterPair = false
                            break
                        }
                    }
                }
            }

            if (isOuterPair && innerDepth == 0) {
                normalized = normalized.substring(1, normalized.length - 1).trim()
            } else {
                break
            }
        }

        return normalized
    }


    /**
     * ## 检查表达式中是否存在顶层的逻辑运算符
     *
     * 这个方法的工作原理：
     * 1. 它遍历表达式中的每个字符，使用变量i作为索引。
     * 2. 当遇到左括号(时，增加depth（深度）计数器，表示进入了一个新的括号层级。
     * 3. 当遇到右括号)时，减少depth计数器，表示退出了一个括号层级。
     * 4. 当满足以下所有条件时，函数返回true：
     *             depth == 0：当前不在任何括号内（即在顶层）
     *             i + operator.length <= expression.length：确保不会越界
     *             expression.substring(i, i + operator.length) == operator：当前位置的子字符串与要查找的运算符匹配
     * 5. 如果遍历完整个表达式都没有找到顶层运算符，函数最终会返回false。
     *
     *     这个方法对于解析复杂的JSONPath过滤表达式非常重要，因为它能够区分顶层的逻辑运算符（如&&和||）和嵌套在括号内的运算符。
     *     例如，在表达式(@.a==1&&@.b==2)||@.c==3中，它能够识别出顶层的||运算符，而不会被括号内的&&运算符干扰。
     *     这是实现复杂嵌套条件过滤的关键部分，使得你的JSONPath查询能够支持像
     *     ```
     *      $.phoneNumbers[?((@.number=="0123-4567-8910"&&@.type=="home2")||@.type=="home")]
     *     ```
     *     这样的复杂表达式。
     */
    private fun hasTopLevelOperator(expression: String, operator: String): Boolean {
        var depth = 0
        var i = 0

        while (i < expression.length) {
            when {
                expression[i] == '(' -> depth++
                expression[i] == ')' -> depth--
                depth == 0 && i + operator.length <= expression.length &&
                        expression.substring(i, i + operator.length) == operator -> {
                    return true
                }
            }
            i++
        }

        return false
    }

    /**
     * 按顶层逻辑运算符分割表达式
     */
    private fun splitByTopLevelOperator(expression: String, operator: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0

        while (i < expression.length) {
            when {
                expression[i] == '(' -> depth++
                expression[i] == ')' -> depth--
                depth == 0 && i + operator.length <= expression.length &&
                        expression.substring(i, i + operator.length) == operator -> {
                    result.add(expression.substring(start, i).trim())
                    start = i + operator.length
                    i += operator.length - 1 // 跳过运算符
                }
            }
            i++
        }

        // 添加最后一部分
        if (start < expression.length) {
            result.add(expression.substring(start).trim())
        }

        return result
    }

    private fun parseCondition(conditionStr: String): PathSegment.Filter.Condition? {
        // Look for comparison operators
        val operators = listOf("<=", ">=", "==", "!=", "<", ">")

        for (op in operators) {
            if (conditionStr.contains(op)) {
                val parts = conditionStr.split(op, limit = 2)
                val left = parts[0].trim()
                val right = parts[1].trim()

                val property = if (left.startsWith("@.")) {
                    left.substring(2)
                } else {
                    left
                }

                val value = try {
                    when {
                        right.toIntOrNull() != null -> right.toInt()
                        right.toDoubleOrNull() != null -> right.toDouble()
                        right == "true" -> true
                        right == "false" -> false
                        right.startsWith("\"") && right.endsWith("\"") ->
                            right.substring(1, right.length - 1)

                        right.startsWith("'") && right.endsWith("'") ->
                            right.substring(1, right.length - 1)

                        else -> right // Keep as string if no quotes
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing condition value: $right", e)
                    right
                }

                return PathSegment.Filter.Condition(property, op, value)
            }
        }

        Log.w(TAG, "Could not parse condition: $conditionStr")
        return null
    }

    /**
     * 评估JSONPath路径并返回匹配的结果
     *
     * 此方法是JSONPath查询的核心实现，它递归地处理JSONPath路径段并在JSON数据中查找匹配项。
     * 方法通过深度优先遍历的方式，根据当前路径段的类型（属性、数组索引、过滤器等）
     * 在JSON结构中导航，并收集所有匹配的结果。
     *
     * 此版本是不带限制参数的简化版本，主要用于内部递归调用。完整版本支持自定义过滤器和结果数量限制。
     *
     * @param reader JSON读取器，指向当前解析位置
     * @param pathSegments 解析后的JSONPath路径段列表
     * @param index 当前处理的路径段索引
     * @return 匹配路径的所有JSON元素列表
     */
    private fun evaluatePath(reader: JsonReader, pathSegments: List<PathSegment>, index: Int): List<Any?> {
        if (index >= pathSegments.size) {
            return listOf(readValue(reader))
        }

        val currentSegment = pathSegments[index]
        val results = mutableListOf<Any?>()

        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()

                when (currentSegment) {
                    is PathSegment.Property -> {
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            if (name == currentSegment.name) {
                                results.addAll(evaluatePath(reader, pathSegments, index + 1))
                            } else {
                                reader.skipValue()
                            }
                        }
                    }

                    is PathSegment.AllElements -> {
                        while (reader.hasNext()) {
                            reader.nextName()
                            results.addAll(evaluatePath(reader, pathSegments, index + 1))
                        }
                    }

                    else -> {
                        // Skip this object as it doesn't match our path
                        while (reader.hasNext()) {
                            reader.nextName()
                            reader.skipValue()
                        }
                    }
                }

                reader.endObject()
            }

            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var arrayIndex = 0
                Log.d(TAG, "currentSegment=$currentSegment")
                when (currentSegment) {
                    is PathSegment.ArrayIndex -> {
                        while (reader.hasNext()) {
                            if (arrayIndex == currentSegment.index) {
                                results.addAll(evaluatePath(reader, pathSegments, index + 1))
                            } else {
                                reader.skipValue()
                            }
                            arrayIndex++
                        }
                    }

                    is PathSegment.AllElements -> {
                        while (reader.hasNext()) {
                            results.addAll(evaluatePath(reader, pathSegments, index + 1))
                            arrayIndex++
                        }
                    }

                    is PathSegment.Filter -> {
                        while (reader.hasNext()) {
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                // We need to check if this object matches the filter
                                val objectValue = readObject(reader)
                                if (matchesFilter(objectValue, currentSegment)) {
                                    // If it matches, we need to evaluate the rest of the path on this object
                                    val tempReader = createTempJsonReader(objectValue)
                                    results.addAll(evaluatePath(tempReader, pathSegments, index + 1))
                                }
                            } else {
                                reader.skipValue()
                            }
                            arrayIndex++
                        }
                    }

                    else -> {
                        // Skip this array as it doesn't match our path
                        while (reader.hasNext()) {
                            reader.skipValue()
                            arrayIndex++
                        }
                    }
                }

                reader.endArray()
            }

            else -> {
                // We've reached a primitive value, but we still have path segments to process
                reader.skipValue()
            }
        }

        return results
    }

    /**
     * 检查对象是否匹配过滤器
     */
    private fun matchesFilter(obj: Map<*, *>, filter: PathSegment.Filter): Boolean {
        // 如果没有条件和子过滤器，返回false
        if (filter.conditions.isEmpty() && filter.subFilters.isEmpty()) return false

        // 处理子过滤器
        if (filter.subFilters.isNotEmpty()) {
            return when (filter.logicalOperator) {
                "&&" -> filter.subFilters.all { matchesFilter(obj, it) }
                "||" -> filter.subFilters.any { matchesFilter(obj, it) }
                else -> false
            }
        }

        // 处理简单条件
        return when (filter.logicalOperator) {
            "&&" -> filter.conditions.all { matchesCondition(obj, it) }
            "||" -> filter.conditions.any { matchesCondition(obj, it) }
            else -> false
        }
    }

    private fun matchesCondition(obj: Map<*, *>, condition: PathSegment.Filter.Condition): Boolean {
        val value = obj[condition.property] ?: return false

        return when (condition.operator) {
            "<" -> {
                when {
                    value is Int && condition.value is Int -> value < condition.value
                    value is Double && condition.value is Double -> value < condition.value
                    value is Int && condition.value is Double -> value < condition.value
                    value is Double && condition.value is Int -> value < condition.value.toDouble()
                    else -> false
                }
            }

            "<=" -> {
                when {
                    value is Int && condition.value is Int -> value <= condition.value
                    value is Double && condition.value is Double -> value <= condition.value
                    value is Int && condition.value is Double -> value <= condition.value
                    value is Double && condition.value is Int -> value <= condition.value.toDouble()
                    else -> false
                }
            }

            "==" -> value == condition.value

            ">=" -> {
                when {
                    value is Int && condition.value is Int -> value >= condition.value
                    value is Double && condition.value is Double -> value >= condition.value
                    value is Int && condition.value is Double -> value >= condition.value
                    value is Double && condition.value is Int -> value >= condition.value.toDouble()
                    else -> false
                }
            }

            ">" -> {
                when {
                    value is Int && condition.value is Int -> value > condition.value
                    value is Double && condition.value is Double -> value > condition.value
                    value is Int && condition.value is Double -> value > condition.value
                    value is Double && condition.value is Int -> value > condition.value.toDouble()
                    else -> false
                }
            }

            "!=" -> value != condition.value

            else -> false
        }
    }

    private fun readValue(reader: JsonReader): Any? {
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> readObject(reader)
            JsonToken.BEGIN_ARRAY -> readArray(reader)
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> {
                val stringValue = reader.nextString()
                stringValue.toIntOrNull() ?: stringValue.toDoubleOrNull() ?: stringValue
            }

            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }

            else -> {
                reader.skipValue()
                null
            }
        }
    }

    private fun readObject(reader: JsonReader): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            result[name] = readValue(reader)
        }
        reader.endObject()
        return result
    }

    private fun readArray(reader: JsonReader): List<Any?> {
        val result = mutableListOf<Any?>()
        reader.beginArray()
        while (reader.hasNext()) {
            result.add(readValue(reader))
        }
        reader.endArray()
        return result
    }

    private fun createTempJsonReader(value: Any?): JsonReader {
        // Convert the value to JSON and create a new JsonReader
        val json = Gson().toJson(value)
        return JsonReader(InputStreamReader(json.byteInputStream(), StandardCharsets.UTF_8))
    }


    /**
     * Loads a JSON file into a memory-mapped byte buffer for efficient reading.
     *
     * This function creates a memory-mapped representation of the specified JSON file,
     * which provides more efficient access to the file contents compared to traditional
     * file I/O operations. The file is mapped in read-only mode to prevent accidental
     * modifications.
     *
     * @param jsonFile The File object representing the JSON file to be loaded
     * @throws FileNotFoundException If the specified file does not exist
     */
    private fun mapJsonFileToByteBuffer(jsonFile: File) {

        if (!jsonFile.exists()) {
            println("File does not exist: $jsonFile")
            throw FileNotFoundException("File does not exist: $jsonFile")
        }
        // Using memory mapping to open the file
        fileChannel = FileInputStream(jsonFile).channel
        mappedByteBuffer = fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            0,
            jsonFile.length()
        )
    }

    /**
     * Creates a JsonReader from a memory-mapped byte buffer.
     *
     * This function creates a JsonReader that reads from the provided MappedByteBuffer,
     * allowing for efficient streaming parsing of JSON data directly from memory without
     * additional file I/O operations. It wraps the buffer in a custom InputStream
     * implementation that reads bytes sequentially from the buffer.
     *
     * @param mappedByteBuffer The memory-mapped byte buffer containing JSON data to be parsed.
     *                         The buffer's position will be advanced as data is read.
     * @return A JsonReader instance configured to read from the provided buffer using UTF-8 encoding.
     *         This reader can be used for streaming JSON parsing operations.
     */
    private fun createJsonReader(mappedByteBuffer: MappedByteBuffer): JsonReader {
        // Create an InputStreamReader from the mapped buffer
        val reader = InputStreamReader(
            object : InputStream() {

                override fun read(): Int {
                    if (!mappedByteBuffer.hasRemaining()) return -1
                    return mappedByteBuffer.get().toInt() and 0xFF
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!mappedByteBuffer.hasRemaining()) return -1
                    val bytesToRead = minOf(len, mappedByteBuffer.remaining())
                    mappedByteBuffer.get(b, off, bytesToRead)
                    return bytesToRead
                }
            },
            StandardCharsets.UTF_8
        )
        return JsonReader(reader)
    }


    /**
     * Defines the different types of path segments used in JSONPath parsing.
     * These segments represent the various components that can appear in a JSONPath expression,
     * allowing for navigation through JSON structures.
     */
    sealed class PathSegment {
        /**
         * Represents a property (key) in a JSON object.
         *
         * @property name The name of the property to match in the JSON object.
         */
        data class Property(val name: String) : PathSegment()

        /**
         * Represents a specific index in a JSON array.
         *
         * @property index The zero-based index position to access in the array.
         */
        data class ArrayIndex(val index: Int) : PathSegment()

        class Filter(
            val conditions: List<Condition> = emptyList(),
            val logicalOperator: String = "&&",
            val subFilters: List<Filter> = emptyList() // 添加子过滤器支持
        ) : PathSegment() {
            data class Condition(val property: String, val operator: String, val value: Any?)

            override fun toString(): String {
                return "Filter(conditions=$conditions, operator=$logicalOperator, subFilters=$subFilters)"
            }
        }

        /**
         * Represents a wildcard that matches all elements in an array or all properties in an object.
         * Used with the "*" notation in JSONPath.
         */
        object AllElements : PathSegment() {
            override fun toString(): String {
                return "AllElements[*]"
            }
        }
    }
}