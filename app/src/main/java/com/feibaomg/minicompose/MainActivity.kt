package com.feibaomg.minicompose

import android.content.res.AssetManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.feibaomg.minicompose.ui.theme.MiniComposeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiniComposeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column {
                        Button(modifier = Modifier.padding(innerPadding), onClick = { testJsonQuery() }) {
                            Text(text = "testJsonQuery")
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(modifier = Modifier.padding(innerPadding), onClick = { releaseJsonQuery() }) {
                            Text(text = "releaseJsonQuery")
                        }
                    }
                }
            }
        }
    }

    private fun releaseJsonQuery() {
        kjQuery?.release()
    }

    var file: File? = null
    var kjQuery: KJsonQuery? = null

    private fun testJsonQuery() {

        MainScope().launch(Dispatchers.IO) {
            if (file == null)
                file = File(filesDir, "excel.json")

            if (!File(getExternalFilesDir(null), "excel.json").exists()) {
                assets.open("excel.json").copyTo(file!!.outputStream())
            }

            val start = System.currentTimeMillis()
            kjQuery = KJsonQuery.getInstance(file!!.absolutePath)
            var result1 = kjQuery!!.cacheArrayField("$.IniTimer.data")

            var result1a = kjQuery!!.query("$.IniTimer.data[?(@.fixEventID==3)]", limit = 1)

//            var result2 = kjQuery!!.query("$.IniDialogNewFun.data[?(@.dialogueId==439)]", limit = 1)
//
//            var result3 = kjQuery!!.query("$.IniCatapult.data[?(@.roleId==10001)]", limit = 1)
//
//            var result4 = kjQuery!!.query("$.IniTopicDialog.data[?(@.roleId==20001&&@.wallContent==10002)]", limit = 1)
//
//            var result5 = kjQuery!!.query("$.IniWeatherContent.data[?(@.whType==2)]", limit = 1)

            Log.d("MainActivity", "query time: ${System.currentTimeMillis() - start}ms")

            Log.d("MainActivity", "IniTimer: $result1a")
//            Log.d("MainActivity", "IniDialogNewFun: $result2")
//            Log.d("MainActivity", "IniCatapult: $result3")
//            Log.d("MainActivity", "IniDialogue: $result4")
//            Log.d("MainActivity", "IniWeatherContent: $result5")
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MiniComposeTheme {
        Greeting("Android")
    }
}