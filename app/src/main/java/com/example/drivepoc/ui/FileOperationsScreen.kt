@file:Suppress("DEPRECATION")

package com.example.drivepoc

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.drivepoc.ui.theme.DrivePOCTheme
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.drive.DriveScopes
import java.io.ByteArrayInputStream
import java.io.InputStream

class FileOperationsActivity : ComponentActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private var driveService: Drive? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(DriveScopes.DRIVE))
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        // Fetch Google account from sign-in
        val account = GoogleSignIn.getLastSignedInAccount(this)
        account?.let {
            initializeDriveService(it)
        }

        setContent {
            DrivePOCTheme {
                // Set the background color to white for the entire surface
                Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                    FileOperationsScreen(driveService)
                }
            }
        }
    }

    private fun initializeDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            this, listOf(DriveScopes.DRIVE)
        ).apply {
            selectedAccount = account.account
        }

        driveService = Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            JacksonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("DrivePoc")
            .build()
    }
}

@Composable
fun FileOperationsScreen(driveService: Drive?) {
    var fileContent by remember { mutableStateOf("") }
    var uploadedFile by remember { mutableStateOf<File?>(null) }
    var filesList by remember { mutableStateOf<List<File>>(emptyList()) }

    val context = LocalContext.current

    // Fetch files from Drive when screen is displayed
    LaunchedEffect(driveService) {
        driveService?.let { service ->
            fetchFilesFromDrive(service) { files ->
                filesList = files
            }
        }
    }

    // Toast when file is uploaded
    LaunchedEffect(uploadedFile) {
        uploadedFile?.let {
            Toast.makeText(context, "File uploaded: ${it.name}", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White), // Set background color to white
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Files in Drive:", style = MaterialTheme.typography.bodyMedium)

        if (filesList.isNotEmpty()) {
            filesList.forEach { file ->
                Text("File: ${file.name}", modifier = Modifier.padding(vertical = 4.dp))
            }
        } else {
            Text("No files found.", modifier = Modifier.padding(vertical = 8.dp))
        }

        TextField(
            value = fileContent,
            onValueChange = { newText -> fileContent = newText },
            label = { Text("Enter note content") },
            modifier = Modifier.padding(16.dp)
        )

        Button(onClick = {
            if (fileContent.isNotEmpty()) {
                driveService?.let {
                    uploadFile(it, fileContent) { file ->
                        uploadedFile = file
                        // Fetch the updated file list after uploading
                        fetchFilesFromDrive(it) { files ->
                            filesList = files
                        }
                    }
                }
            } else {
                Toast.makeText(context, "File content cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }, modifier = Modifier.padding(top = 16.dp)) {
            Text("Upload Note")
        }

        Button(onClick = {
            fileContent = ""
            Toast.makeText(context, "Note deleted locally", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.padding(top = 16.dp)) {
            Text("Delete Note Locally")
        }

        Button(onClick = {
            uploadedFile?.let { file ->
                restoreFileFromDrive(driveService, file) { restoredFile ->
                    fileContent = "Content Restored: ${restoredFile.name}"
                }
            } ?: Toast.makeText(context, "No file to restore", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.padding(top = 16.dp)) {
            Text("Restore Note from Drive")
        }
    }
}

fun fetchFilesFromDrive(driveService: Drive, onFilesFetched: (List<File>) -> Unit) {
    Thread {
        try {
            val result = driveService.files().list().execute()
            val files = result.files ?: emptyList()
            onFilesFetched(files)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }.start()
}

fun restoreFileFromDrive(driveService: Drive?, file: File, onRestoreComplete: (File) -> Unit) {
    Thread {
        try {
            val restoredFile = driveService?.files()?.get(file.id)?.execute()
            restoredFile?.let {
                onRestoreComplete(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }.start()
}

fun uploadFile(driveService: Drive, content: String, onUploadComplete: (File) -> Unit) {
    val fileMetadata = File().apply { name = content }
    val inputStream: InputStream = ByteArrayInputStream(content.toByteArray())

    Thread {
        try {
            val mediaContent = com.google.api.client.http.InputStreamContent("text/plain", inputStream)
            val file = driveService.files().create(fileMetadata, mediaContent).execute()
            onUploadComplete(file)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }.start()
}
