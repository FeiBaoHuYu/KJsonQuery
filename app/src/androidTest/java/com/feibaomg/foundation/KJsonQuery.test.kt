package com.feibaomg.foundation

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNotSame
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertSame
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.fail
import org.junit.Assert
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import kotlin.jvm.java
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class KJsonQueryTest {


    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        //为方便维护和AI代码生成，这个用于测试的json内容与assets的store.json中的内容
        // 完全相同,修改这里时也要同步修改store.json.
        val jsonContent = """
        {
          "store": {
            "name": "bookstore",
            "close_days": [6, 7, 13, 14, 21, 22],
            "book": [
              {
                "category": "reference",
                "author": "Nigel Rees",
                "title": "Sayings of the Century",
                "price": 8.95
              },
              {
                "category": "fiction",
                "author": "Evelyn Waugh",
                "title": "Sword of Honour",
                "price": 12.99
              },
              {
                "category": "fiction",
                "author": "Evelyn Waugh",
                "title": "48 hour around the world",
                "price": 13.59
              },
              {
                "category": "历史",
                "author": "张三",
                "title": "南北朝史",
                "price": 23.59
              },
              {
                "category": "历史",
                "author": "太史公",
                "title": "史记",
                "price": 5.59
              },
              {
                "category": "数学",
                "author": "张骞",
                "title": "高等数学",
                "price": 33.99
              },
              {
                "category": "数学",
                "author": "张骞",
                "title": "微积分",
                "price": 53.99
              }
            ],
            "bicycle": {
              "color": "red",
              "price": 19.95,
              "features": ["speed", "comfort", "safety"]
            }
          },
          "expensive": 10
        }
        """.trimIndent()

        testJsonFile = File(context.filesDir, "test_complex.json")
        FileOutputStream(testJsonFile).use { it.write(jsonContent.toByteArray()) }

        kJsonQuery = KJsonQuery.getOrCreate(testJsonFile)
    }

    @Test
    fun test_select_a_complex_json_object() {
        // Call the select method with a path
        val result = kJsonQuery.select("$.store").execute()
        //查询结果永远是一个list，liist里面可能是Map<String, Any>，也可能是string或者数字(double类型
        val store = (result as List<Map<*, *>>)[0]

        assertEquals("bookstore", store["name"])

        val books = store["book"] as List<Map<*, *>>

        assertEquals(7, books.size)
        assertEquals("Sayings of the Century", books[0]["title"])

        val bicycle = store["bicycle"] as Map<String, *>

        assertEquals("red", bicycle["color"])
        assertEquals(19.95, bicycle["price"])

        val features = bicycle["features"] as List<*>

        assertEquals(3, features.size)
        assertEquals("speed", features[0])
        assertEquals("comfort", features[1])
        assertEquals("safety", features[2])
    }

    @Test
    fun test_select_number_arrays() {
        // Call the select method with a path
        val results = kJsonQuery.select("$.store.close_days").execute()
        val expected = listOf(6, 7, 13, 14, 21, 22)

        assertNotNull(results)
        assertEquals(6, results.size)
        assertEquals(expected[0], (results as List<Int>)[0])
        assertEquals(expected[1], (results as List<Int>)[1])
        assertEquals(expected[2], (results as List<Int>)[2])
        assertEquals(expected[3], (results as List<Int>)[3])
        assertEquals(expected[4], (results as List<Int>)[4])
        assertEquals(expected[5], (results as List<Int>)[5])
    }

    @Test
    fun testQueryBuilderMapMethod() {

        class Book(val category:String, val title: String, val author: String, val price: Double)

        // Use map to transform each user object to just their name
        val names = kJsonQuery.select("$.store.book")
            .map { it ->
                val map = it as Map<*, *>

                Book(
                    map["category"] as String,
                    map["title"] as String,
                    map["author"] as String,
                    map["price"] as Double
                )
                // 也可以用Gson库转换成Book类
//                val jsonStr =Gson().toJson (userObj as Map<*, *>)
//                Gson().fromJson(jsonStr, Book::class.java)
            }

        // Verify the transformation was applied correctly
        assertEquals(7, names.size)
        assertEquals(true, names[0] is Book)

        val book1 = names[0] as Book

        assertEquals("reference", book1.category)
        assertEquals("Nigel Rees", book1.author)
        assertEquals("Sayings of the Century", book1.title)
        assertEquals(8.95, book1.price)
    }

    /**
     * 类似SQL的查询语法示例
     */
    @Test
    fun test_select_with_path_and_filter_with_where_statement() {
        val myMoney: Double = 25.toDouble()
        // Call the select method with a path
        val results = kJsonQuery.select("""$.store.book[?(@.category=="历史")]""")
            .where {
                //转换成map
                val book = it as Map<*, *>
                // 模拟外部条件判断
                (book["price"] as Double) <= myMoney
            }
            .limit(1)
            .execute()

        assertNotNull(results)
        assertEquals(1, results.size)
        assertEquals("历史", (results[0] as Map<*, *>)["category"])
    }


    @Test
    fun testQueryBuilderFirstAndFirstOrNull() {

        // Test finding a result using firstOrNull()
        val result = kJsonQuery.select("$.store.book")
            .where { it ->
                it is Map<*, *> && it["title"] == "48 hour around the world"
            }
            .firstOrNull()

        assertNotNull("Should find book 48 hour around the world", result)
        assertTrue("Result should be a Map", result is Map<*, *>)
        assertEquals("Should have price", 13.59, (result as Map<*, *>)["price"])

        // Test not finding a result using firstOrNull() (should return null)
        val nonExistentBook = kJsonQuery.select("$.store.book")
            .where { result ->
                result is Map<*, *> && result["title"] == "NonExistent"
            }
            .firstOrNull()

        assertNull("Should return null for non-existent book", nonExistentBook)

        // Test exception when using first() with no results
        try {
            kJsonQuery.select("$.store.book")
                .where { result ->
                    result is Map<*, *> && result["title"] == "NonExistent"
                }
                .first()

            fail("Should throw NoSuchElementException")
        } catch (e: NoSuchElementException) {
            // Expected exception
        }
    }

    @Test
    fun test_wild_char_to_get_all_array_elements() {
        // Test wildcard access for all books
        val allBooks = kJsonQuery.query("$.store.book[*]")
        println(allBooks)
        assertEquals(7, allBooks.size)
    }

    @Test
    fun test_object_property_access() {
        // Test accessing nested object properties
        val storeResult = kJsonQuery.query("$.store") // List<Map<String, Any>>
        println(storeResult)  // Prints: [{book=[{...]}]
        assertEquals(1, storeResult.size)
    }

    @Test
    fun test_access_array_elements() {
        val bikeFeatures = kJsonQuery.query("$.store.bicycle.features")  // List<String>
        println(bikeFeatures)  // Prints: [speed, comfort, safety]
        assertEquals(3, bikeFeatures.size)
        assertEquals("speed", bikeFeatures[0])
    }

    @Test
    fun test_access_array_elements_with_number_filter() {
        // Test accessing nested array with complex filter (price > 10)
        val expensiveBooks = kJsonQuery.query("$.store.book[?(@.price>10)]") as List<Map<String, *>>
        assertEquals(5, expensiveBooks.size)
        assertEquals("Sword of Honour", expensiveBooks[0]["title"])
    }

    @Test
    fun test_access_array_elements_with_specific_index() {
        // Test accessing array with specific index
        val firstBookResult = kJsonQuery.query("$.store.book[0]") as List<Map<String, *>>
        assertEquals("Nigel Rees", firstBookResult[0]["author"])
    }

    @Test
    fun test_access_array_elements_with_string_compare_filter() {
        // Test accessing nested array with property filter
        val fictionBooks = kJsonQuery.query("""$.store.book[?(@.category=="fiction")]""") as List<Map<String, *>>
        assertEquals(2, fictionBooks.size)
        assertEquals("Evelyn Waugh", fictionBooks[0]["author"])
    }

    @Test
    fun test_access_array_elements_with_multiple_property_filters() {
        val twoCategoryBooks = kJsonQuery.query("""$.store.book[?(@.category=="数学"||@.category=="历史")]""") as List<Map<String, *>>
        assertEquals(4, twoCategoryBooks!!.size)
        var historyBook = twoCategoryBooks.find { it["title"] == "南北朝史" } as? Map<*, *>
        assertNotNull(historyBook)
        assertEquals(historyBook!!["author"], "张三")

        val mathBook = twoCategoryBooks.find { it["title"] == "高等数学" } as? Map<*, *>
        assertNotNull(mathBook)
        assertEquals(mathBook!!["author"], "张骞")
    }

    @Test
    fun test_access_array_elements_with_complex_nested_one_filter() {

        val books = kJsonQuery.query("""$.store.book[?((@.category=="数学"&&@.price>50)||@.category=="历史")]""") as List<Map<String, *>>
        println(books)
        assertNotNull(books)
        assertEquals(3, books.size)
        var historyBooks = books.filter { it["category"] == "历史" }
        var mathBooks = books.filter { it["category"] == "数学" }
        assertEquals(2, historyBooks.size)
        assertEquals(1, mathBooks.size)
        assertEquals("微积分", mathBooks[0]["title"])
        assertEquals("南北朝史", historyBooks[0]["title"])

    }

    @Test
    fun test_access_array_elements_with_complex_nested_two_filters() {

        val books = kJsonQuery.query("""$.store.book[?((@.category=="数学"&&@.price>50)||(@.category=="历史"&&@.price<10))]""") as List<Map<String, *>>
        println(books)
        assertNotNull(books)
        assertEquals(2, books.size)
        var historyBooks = books.filter { it["category"] == "历史" }
        var mathBooks = books.filter { it["category"] == "数学" }
        assertEquals(1, historyBooks.size)
        assertEquals(1, mathBooks.size)
        assertEquals("微积分", mathBooks[0]["title"])
        assertEquals("史记", historyBooks[0]["title"])
    }


    @Test
    fun test_throw_exception_when_create_query_with_non_existent_json_file() {
        // Create a non-existent file path

        //FileNotFoundException
        val nonExistentFile = File(context.filesDir, "nonexistent.json")
        if (nonExistentFile.exists()) {
            nonExistentFile.delete()
        }

        Assert.assertThrows("File does not exist: $nonExistentFile", FileNotFoundException::class.java) {
            // Try to create query from non-existent file
            KJsonQuery.getOrCreate(nonExistentFile)
        }
    }

    @Test
    fun test_return_empty_when_query_with_empty_json_file() {

        // Create an empty file
        val emptyFile = File(context.filesDir, "empty.json")
        emptyFile.createNewFile()

        try {
            // Try to query from empty file
            val emptyQuery = KJsonQuery.getOrCreate(emptyFile)
            val emptyResult = emptyQuery.query("$.anyPath")

            // Verify result is empty
            assertEquals(0, emptyResult.size)
        } finally {
            // Clean up
            emptyFile.delete()
        }
    }


    @Test
    fun testCacheArrayFieldImprovePerformance() {
        // First, clear any existing cache
        kJsonQuery.clearArrayCache()

        // Measure time for first query without cache
        val firstQueryTime = measureTimeMillis {
            val result = kJsonQuery.query("$.store.book")
            assertEquals(7, result.size)
        }

        // Cache the array for future queries
        val cachedArray = kJsonQuery.cacheArrayField("$.store.book")
        assertNotNull("Array should be cached successfully", cachedArray)

        // Verify array is cached
        assertTrue(kJsonQuery.isArrayFieldCached("$.store.book"))

        // Measure time for second query with cache
        val secondQueryTime = measureTimeMillis {
            val result = kJsonQuery.query("$.store.book")
            assertEquals(7, result.size) // Should still find Bob and Charlie
        }

        // The second query should be faster due to caching
        assertTrue("Query with cache should be faster than without cache",
            secondQueryTime < firstQueryTime || secondQueryTime < 50) // Allow for small timing variations

        // Test cache invalidation
        kJsonQuery.invalidateArrayCache("$.store.book")
        assertFalse(kJsonQuery.isArrayFieldCached("$.store.book"))
    }

    @Test
    fun testSelectCreatesNewQueryBuilderEachTime() {
        // Call select() twice to get two QueryBuilder instances
        val queryBuilder1 = kJsonQuery.select()
        val queryBuilder2 = kJsonQuery.select()
        
        // Verify that the two instances are different objects
        assertNotSame("Should create a new QueryBuilder instance each time", queryBuilder1, queryBuilder2)
        
        // Add different paths to each builder to further demonstrate they are independent
        queryBuilder1.from("$.store.book")
        queryBuilder2.from("$.store.bicycle")
        
        // Execute both queries
        val result1 = queryBuilder1.execute()
        val result2 = queryBuilder2.execute()
        
        // Verify results are different, showing the instances are independent
        assertNotEquals(result1, result2)
    }




    private lateinit var context: Context
    private lateinit var testJsonFile: File
    private lateinit var kJsonQuery: KJsonQuery

}