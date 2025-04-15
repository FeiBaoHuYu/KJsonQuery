package com.feibaomg.minicompose

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
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
                    Button(modifier = Modifier.padding(innerPadding), onClick = {  testJsonQuery()}){
                        Text(text = "testJsonQuery")
                    }
                }
            }
        }
    }

    private fun testJsonQuery() {
        MainScope().launch(Dispatchers.IO) {
            val file = File(filesDir,"excel.json")
//            assets.open("excel.json").copyTo(file.outputStream())
            val start = System.currentTimeMillis()
            val kjQuery = KJsonQuery(file)
            var result1 = kjQuery.query("$.IniTimer.data[?(@.fixEventID==2)]")
            kjQuery.rewindBuffer()
            var result2 = kjQuery.query("$.IniDialogNewFun.data[?(@.dialogueId==439)]")
            kjQuery.rewindBuffer()
            var result3 = kjQuery.query("$.IniCatapult.data[?(@.roleId==10001)]")
            kjQuery.rewindBuffer()
            var result4 = kjQuery.query("$.IniDialogue.data[?(@.iD==1)]")
            kjQuery.rewindBuffer()
            var result5 = kjQuery.query("$.IniWeatherContent.data[?(@.whType==2)]")
            kjQuery.release()
            Log.d("MainActivity", "query time: ${System.currentTimeMillis() - start}ms")

            Log.d("MainActivity", "IniTimer: $result1")
            Log.d("MainActivity", "IniDialogNewFun: $result2")
            Log.d("MainActivity", "IniCatapult: $result3")
            Log.d("MainActivity", "IniDialogue: $result4")
            Log.d("MainActivity", "IniWeatherContent: $result5")
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