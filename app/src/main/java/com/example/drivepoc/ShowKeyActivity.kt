@file:Suppress("DEPRECATION")

package com.example.drivepoc

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import com.example.drivepoc.objects.KeyPairManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.drivepoc.objects.KeyStoreObjects
import com.example.drivepoc.ui.theme.DrivePOCTheme
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes

class ShowKeyActivity : ComponentActivity() {

    private var driveService: Drive? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Drive service
        initializeDriveService()

        val publicKey = KeyStoreObjects.getPublicKey()
        Log.d("Unique PublicKey"," $publicKey")

        val privateKey = KeyStoreObjects.getPrivateKey()
        Log.d("Unique PrivateKey"," ${privateKey.toString()}")

        setContent {
            DrivePOCTheme {
                ManageKeysScreen(driveService, this)
            }
        }
    }

    private fun initializeDriveService() {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) {
            val credential = GoogleAccountCredential.usingOAuth2(
                this, listOf(DriveScopes.DRIVE)
            ).apply {
                selectedAccount = account.account
            }

            driveService = Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                JacksonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("DrivePoc").build()
        }
    }
}

@Composable
fun ManageKeysScreen(driveService: Drive?, context: Context) {
    var privateKey by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        driveService?.let {
            KeyPairManager.fetchECCKeysFromDrive(it, context = context) { keyPair ->
                if (keyPair != null) {
                    privateKey = keyPair.private.toString()
                    publicKey = keyPair.public.toString()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Private Key:", style = MaterialTheme.typography.bodyLarge)
        Text(privateKey, modifier = Modifier.padding(8.dp))

        Text("Public Key:", style = MaterialTheme.typography.bodyLarge)
        Text(publicKey, modifier = Modifier.padding(8.dp))
    }
}
