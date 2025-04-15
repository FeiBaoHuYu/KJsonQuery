package com.feibaomg.minicompose

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
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
import kotlin.collections.get
import kotlin.text.iterator



private const val TAG = "KJsonQuery"
class KJsonQuery {
    private lateinit var fileChannel: FileChannel
    private lateinit var mappedByteBuffer: MappedByteBuffer

    constructor(filepath: String) {
        createJsonReader(File(filepath))
    }

    constructor(file: File){
        createJsonReader(file)
    }

    fun query(jsonPath: String = "$"): List<Any?> {
        try {
            // Create a Gson JsonReader for streaming parsing
            val jsonReader = createJsonReader(mappedByteBuffer)

            // Query JSON with the provided JSONPath and custom filter
            val results = queryJson(jsonReader, jsonPath)

            // Close resources
            jsonReader.close()

            return results
        } catch (e: Exception) {
            println("Error processing JSON file: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    private fun queryJson(reader: JsonReader, jsonPath: String): List<Any?> {
        val pathSegments = parseJsonPath(jsonPath)
        Log.d(TAG,"Path segments: $pathSegments")
        val results = evaluatePath(reader, pathSegments, 0)
        Log.d(TAG,"results: $results")
        // Apply custom filter if provided
        return results
    }

    private fun evaluatePath(
        reader: JsonReader,
        pathSegments: List<PathSegment>,
        index: Int,
        customFilter: ((Any?) -> Boolean)? = null
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
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            if (name == currentSegment.name) {
                                results.addAll(evaluatePath(reader, pathSegments, index + 1, customFilter))
                            } else {
                                reader.skipValue()
                            }
                        }
                    }
                    is PathSegment.AllElements -> {
                        while (reader.hasNext()) {
                            reader.nextName()
                            results.addAll(evaluatePath(reader, pathSegments, index + 1, customFilter))
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
                                results.addAll(evaluatePath(reader, pathSegments, index + 1, customFilter))
                            } else {
                                reader.skipValue()
                            }
                            arrayIndex++
                        }
                    }
                    is PathSegment.AllElements -> {
                        while (reader.hasNext()) {
                            results.addAll(evaluatePath(reader, pathSegments, index + 1, customFilter))
                            arrayIndex++
                        }
                    }
                    is PathSegment.Filter -> {
                        while (reader.hasNext()) {
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                // We need to check if this object matches the filter
                                val objectValue = readObject(reader)
                                if (objectValue is Map<*, *> && matchesFilter(objectValue, currentSegment)) {
                                    // If it matches, we need to evaluate the rest of the path on this object
                                    val tempReader = createTempJsonReader(objectValue)
                                    results.addAll(evaluatePath(tempReader, pathSegments, index + 1, customFilter))
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

    private fun parseJsonPath(jsonPath: String): List<PathSegment> {
        val segments = mutableListOf<PathSegment>()

        // Remove the root symbol if present
        val path = if (jsonPath.startsWith("$")) jsonPath.substring(1) else jsonPath

        // Split by dots, but handle array notation properly
        var currentSegment = ""
        //in []
        var inBracket = false
        //()
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
                        Log.d(TAG,"Current segment: $currentSegment")
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
        // Basic filter parsing for expressions like (@.price < 10)
        if (filter.startsWith("(") && filter.endsWith(")")) {
            val expression = filter.substring(1, filter.length - 1).trim()

            // Look for comparison operators
            val operators = listOf("<", "<=", "==", ">=", ">", "!=")
            for (op in operators) {
                if (expression.contains(op)) {
                    val parts = expression.split(op, limit = 2)
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
                            right == "true" || right == "false" -> right.toBoolean()
                            right.startsWith("'") && right.endsWith("'") ->
                                right.substring(1, right.length - 1)
                            right.startsWith("\"") && right.endsWith("\"") ->
                                right.substring(1, right.length - 1)
                            else -> right
                        }
                    } catch (e: Exception) {
                        right
                    }

                    return PathSegment.Filter(property, op, value)
                }
            }
        }

        // Default to a no-op filter if we can't parse it
        return PathSegment.Filter("", "==", "")
    }

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
                Log.d(TAG,"currentSegment=$currentSegment")
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

    private fun matchesFilter(obj: Map<*, *>, filter: PathSegment.Filter): Boolean {
        val value = obj[filter.property] ?: return false

        return when (filter.operator) {
            "<" -> {
                when {
                    value is Int && filter.value is Int -> value < filter.value
                    value is Double && filter.value is Double -> value < filter.value
                    value is Int && filter.value is Double -> value < filter.value
                    value is Double && filter.value is Int -> value < filter.value.toDouble()
                    else -> false
                }
            }
            "<=" -> {
                when {
                    value is Int && filter.value is Int -> value <= filter.value
                    value is Double && filter.value is Double -> value <= filter.value
                    value is Int && filter.value is Double -> value <= filter.value
                    value is Double && filter.value is Int -> value <= filter.value.toDouble()
                    else -> false
                }
            }
            "==" -> value == filter.value
            ">=" -> {
                when {
                    value is Int && filter.value is Int -> value >= filter.value
                    value is Double && filter.value is Double -> value >= filter.value
                    value is Int && filter.value is Double -> value >= filter.value
                    value is Double && filter.value is Int -> value >= filter.value.toDouble()
                    else -> false
                }
            }
            ">" -> {
                when {
                    value is Int && filter.value is Int -> value > filter.value
                    value is Double && filter.value is Double -> value > filter.value
                    value is Int && filter.value is Double -> value > filter.value
                    value is Double && filter.value is Int -> value > filter.value.toDouble()
                    else -> false
                }
            }
            "!=" -> value != filter.value
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

    private fun createJsonReader(jsonFile: File) {

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

    private fun createJsonReader(mappedByteBuffer: MappedByteBuffer): JsonReader {
        // Create an InputStreamReader from the mapped buffer
        val reader = InputStreamReader(
            object : InputStream() {
                private var position = 0

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


    fun filterObjectIntField(result: Any?, key: String): Int? {
        if(result !is Map<*, *>){
            return null
        }
        val resultObjects = result as Map<*, *>
        return resultObjects[key]?.toString()?.toIntOrNull()
    }

    fun filterObjectStringField(result: Any?, key: String): String? {
        if(result !is Map<*, *>){
            return null
        }
        val resultObjects = result as Map<*, *>
        return resultObjects[key]?.toString()
    }

    fun filterDoubleStringField(result: Any?, key: String): Double? {
        if(result !is Map<*, *>){
            return null
        }
        val resultObjects = result as Map<*, *>
        return resultObjects[key]?.toString()?.toDoubleOrNull()
    }

    fun release() {
        if(::mappedByteBuffer.isInitialized) {
            try {
                mappedByteBuffer.clear()
            }catch (_: IOException) {}
        }
        if(::fileChannel.isInitialized) {
            try {
                fileChannel.close()
            }catch (_: IOException) {}
        }
    }

    /**
     *  Rewind the buffer to the beginning for next gson reader reading again
      */
    fun rewindBuffer() {
        if(::mappedByteBuffer.isInitialized) {
            mappedByteBuffer.rewind()
        }
    }

    // Define path segment types for JSONPath parsing
    sealed class PathSegment {
        data class Property(val name: String) : PathSegment()
        data class ArrayIndex(val index: Int) : PathSegment()
        data class Filter(val property: String, val operator: String, val value: Any?) : PathSegment()
        object AllElements : PathSegment()
    }
}