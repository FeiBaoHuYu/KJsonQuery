package com.feibaomg.foundation

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class KJsonQueryTest {

    private lateinit var context: Context
    private lateinit var testJsonFile: File
    private lateinit var kJsonQuery: KJsonQuery

    @Volatile
    var copied = false

    fun copyFile() {
        if(!copied) {
            copied = true
            val fileName = "store.json"
            val file = File(context.getExternalFilesDir(null), fileName)
            context.assets.open(fileName).copyTo(file.outputStream())
        }
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Create a test JSON file with nested objects and arrays
        val jsonContent = """
        {
          "store": {
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

        kJsonQuery = KJsonQuery.getInstance(testJsonFile)
    }

    @Test
    fun test_wild_char_all_array_elements() {
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
    fun test_access_array_elements_within_nested_object() {
        val bikeFeatures = kJsonQuery.query("$.store.bicycle.features")  // List<List<String>>
        println(bikeFeatures)  // Prints: [[speed, comfort, safety]]
        assertEquals(1, bikeFeatures.size)
        assertEquals(3, (bikeFeatures[0] as List<String>).size)
    }
    @Test
    fun test_with_single_filter() {
        // Test accessing nested array with complex filter (price > 10)
        val expensiveBooks = kJsonQuery.query("$.store.book[?(@.price>10)]") as List<Map<String,*>>
        assertEquals(5, expensiveBooks.size)
        assertEquals("Sword of Honour", expensiveBooks[0]["title"])
    }
    @Test
    fun test_access_array_elements_with_specific_index() {
        // Test accessing array with specific index
        val firstBookResult = kJsonQuery.query("$.store.book[0]") as List<Map<String,*>>
        assertEquals("Nigel Rees", firstBookResult[0]["author"])
    }
    @Test
    fun test_access_array_elements_with_property_filter() {
        // Test accessing nested array with property filter
        val fictionBooks = kJsonQuery.query("""$.store.book[?(@.category=="fiction")]""")  as List<Map<String,*>>
        assertEquals(2, fictionBooks.size)
        assertEquals("Evelyn Waugh", fictionBooks[0]["author"])
    }
    @Test
    fun test_access_array_elements_with_multiple_property_filters() {
        val twoCategoryBooks = kJsonQuery.query("""$.store.book[?(@.category=="数学"||@.category=="历史")]""")  as List<Map<String,*>>
        assertEquals(4, twoCategoryBooks!!.size)
        var historyBook = twoCategoryBooks.find { it["title"] == "南北朝史" } as? Map<*, *>
        assertNotNull(historyBook)
        assertEquals(historyBook!!["author"], "张三")

        val mathBook = twoCategoryBooks.find { it["title"] == "高等数学" } as? Map<*, *>
        assertNotNull(mathBook)
        assertEquals(mathBook!!["author"], "张骞")
    }

    @Test
    fun test_complex_one_nested_filters() {

        val books = kJsonQuery.query("""$.store.book[?((@.category=="数学"&&@.price>50)||@.category=="历史")]""") as List<Map<String,*>>
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
    fun test_complex_two_nested_filters() {

        val books = kJsonQuery.query("""$.store.book[?((@.category=="数学"&&@.price>50)||(@.category=="历史"&&@.price<10))]""") as List<Map<String,*>>
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
    fun test_empty_or_non_existent_json_file() {
        // Create a non-existent file path
        val nonExistentFile = File(context.filesDir, "nonexistent.json")
        if (nonExistentFile.exists()) {
            nonExistentFile.delete()
        }

        // Try to query from non-existent file
        val query = KJsonQuery.getInstance(nonExistentFile)
        val result = query.query("$.anyPath")

        // Verify result is empty
        assertEquals(0, result.size)

        // Create an empty file
        val emptyFile = File(context.filesDir, "empty.json")
        emptyFile.createNewFile()

        try {
            // Try to query from empty file
            val emptyQuery = KJsonQuery.getInstance(emptyFile)
            val emptyResult = emptyQuery.query("$.anyPath")

            // Verify result is empty
            assertEquals(0, emptyResult.size)
        } finally {
            // Clean up
            emptyFile.delete()
        }
    }
}