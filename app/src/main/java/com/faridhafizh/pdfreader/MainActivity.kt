package com.faridhafizh.pdfreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.faridhafizh.pdfreader.ui.library.LibraryScreen
import com.faridhafizh.pdfreader.ui.library.LibraryViewModel
import com.faridhafizh.pdfreader.ui.reader.ReaderScreen
import com.faridhafizh.pdfreader.ui.reader.ReaderViewModel
import com.faridhafizh.pdfreader.ui.scan.ScanScreen
import com.faridhafizh.pdfreader.ui.scan.ScanViewModel
import com.faridhafizh.pdfreader.ui.signatures.SignaturesScreen
import com.faridhafizh.pdfreader.ui.signatures.SignaturesViewModel
import com.faridhafizh.pdfreader.ui.theme.PdfReaderTheme

class MainActivity : ComponentActivity() {
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incomingUri = extractUri(intent)
        val container = (application as PdfReaderApp).container
        setContent {
            PdfReaderTheme {
                AppNav(container, incomingUri) { incomingUri = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingUri = extractUri(intent)
    }

    private fun extractUri(i: Intent?): Uri? = if (i?.action == Intent.ACTION_VIEW) i.data else null
}

@Composable
private fun AppNav(container: AppContainer, incomingUri: Uri?, onIncomingConsumed: () -> Unit) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "library") {
        composable("library") {
            val vm: LibraryViewModel = viewModel(factory = viewModelFactory {
                initializer { LibraryViewModel(container) }
            })
            LibraryScreen(
                vm = vm,
                incomingUri = incomingUri,
                onIncomingConsumed = onIncomingConsumed,
                onOpen = { id -> nav.navigate("reader/$id") },
                onScan = { nav.navigate("scan") },
                onSignatures = { nav.navigate("signatures") },
            )
        }
        composable("scan") {
            val vm: ScanViewModel = viewModel(factory = viewModelFactory {
                initializer { ScanViewModel(container) }
            })
            ScanScreen(vm, onBack = { nav.popBackStack() }, onSaved = { id -> nav.navigate("reader/$id") { popUpTo("library") } })
        }
        composable("signatures") {
            val vm: SignaturesViewModel = viewModel(factory = viewModelFactory {
                initializer { SignaturesViewModel(container) }
            })
            SignaturesScreen(vm, onBack = { nav.popBackStack() })
        }
        composable(
            route = "reader/{docId}",
            arguments = listOf(navArgument("docId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments!!.getLong("docId")
            val vm: ReaderViewModel = viewModel(key = "reader$id", factory = viewModelFactory {
                initializer { ReaderViewModel(container, id) }
            })
            ReaderScreen(vm) { nav.popBackStack() }
        }
    }
}
