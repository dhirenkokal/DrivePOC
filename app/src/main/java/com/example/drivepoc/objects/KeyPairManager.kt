package com.example.drivepoc.objects

import android.content.Context
import android.os.Build
import android.security.keystore.KeyProperties
import android.security.keystore.KeyGenParameterSpec
import android.util.Base64
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.FileList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object KeyPairManager {

    private const val KEY_ALIAS = "ECC_Key_Pair"

    fun fetchECCKeysFromDrive(driveService: Drive, context: Context, callback: (KeyPair?) -> Unit) {
        val keyPair = retrieveECCKeyPairFromKeystore()
        if (keyPair != null) {
            Log.d("KeyPairUtils", "Keys already exist in Keystore.")
            callback(keyPair)
            return
        }

        // Launch a coroutine to perform the Drive operation asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Search for the file in Drive containing ECC keys
                val query = "name = 'ECC_Key_Pair' and mimeType = 'text/plain'"
                val result: FileList = driveService.files().list().setQ(query).setSpaces("drive").execute()
                val driveFile: com.google.api.services.drive.model.File? = result.files.firstOrNull()

                if (driveFile != null) {
                    // Download file content
                    val inputStream = driveService.files().get(driveFile.id).executeMediaAsInputStream()
                    val fileContent = inputStream.bufferedReader().use { it.readText() }

                    // Extract public key (Keystore doesn't support importing private keys)
                    val publicKeyBase64 = fileContent.trim()

                    // Store the public key in Keystore
                    val publicKey = decodePublicKey(publicKeyBase64)
                    val privateKey = generateECCPrivateKeyInKeystore()

                    if (publicKey != null && privateKey != null) {
                        callback(KeyPair(publicKey, privateKey))
                    } else {
                        callback(null)
                    }
                } else {
                    // Generate new keys if file is not found
                    Log.d("KeyPairUtils", "Key file not found in Drive. Generating new keys.")
                    val keyPair = generateECCKeyPairInKeystore()
                    uploadECCKeysToDrive(driveService, keyPair)
                    callback(keyPair)
                }
            } catch (e: UserRecoverableAuthIOException) {
                Log.e("KeyPairUtils", "Authorization required: ${e.message}")
                val intent = e.intent
                context.startActivity(intent)
                callback(null)
            } catch (e: Exception) {
                Log.e("KeyPairUtils", "Error fetching keys from Drive", e)
                callback(null)
            }
        }
    }

    private fun retrieveECCKeyPairFromKeystore(): KeyPair? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey

            if (privateKey != null && publicKey != null) {
                KeyPair(publicKey, privateKey)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("KeyPairUtils", "Error retrieving key pair from Keystore", e)
            null
        }
    }

    private fun generateECCKeyPairInKeystore(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setUserAuthenticationRequired(false)
            .build()

        keyPairGenerator.initialize(keyGenParameterSpec)
        return keyPairGenerator.generateKeyPair()
    }

    fun generateECCKeyPairInKeystore(alias: String) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        // Check if the key pair already exists
        if (keyStore.containsAlias(alias)) {
            // The key pair is already present, no need to generate it again
            return
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            keyPairGenerator.initialize(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_AGREE_KEY // Purpose for KeyAgreement
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")) // Curve spec
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false) // Optional: Add authentication if needed
                    .build()
            )
        }
        keyPairGenerator.generateKeyPair()
    }

    private fun decodePublicKey(publicKeyBase64: String): PublicKey? {
        return try {
            val keyBytes = Base64.decode(publicKeyBase64, Base64.DEFAULT)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            keyFactory.generatePublic(keySpec)
        } catch (e: Exception) {
            Log.e("KeyPairUtils", "Error decoding public key", e)
            null
        }
    }

    private fun uploadECCKeysToDrive(driveService: Drive, keyPair: KeyPair) {
        try {
            val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.DEFAULT)

            val fileMetadata = com.google.api.services.drive.model.File()
            fileMetadata.name = "ECC_Key_Pair"
            fileMetadata.mimeType = "text/plain"

            val fileContentStream = publicKeyBase64.byteInputStream()
            val mediaContent = com.google.api.client.http.InputStreamContent("text/plain", fileContentStream)
            driveService.files().create(fileMetadata, mediaContent).execute()

            Log.d("KeyPairUtils", "Public key successfully uploaded to Drive.")
        } catch (e: Exception) {
            Log.e("KeyPairUtils", "Error uploading public key to Drive", e)
        }
    }

    fun encryptTextWithECC(context: Context, plainText: String): String? {
        try {
            val keyPair = retrieveECCKeyPairFromKeystore()
            if (keyPair == null) {
                Log.e("KeyPairUtils", "Key pair not found in Keystore. Unable to encrypt.")
                return null
            }

            val publicKey = keyPair.public
            val privateKey = keyPair.private

            // Simulate the recipient's public key (in real scenarios, obtain this from the recipient)
            val recipientKeyPair = generateRecipientECCKeyPair()
            val recipientPublicKey = recipientKeyPair.public

            // Perform ECDH key agreement to derive a shared secret
            val keyAgreement = KeyAgreement.getInstance("ECDH")
            keyAgreement.init(privateKey)
            keyAgreement.doPhase(recipientPublicKey, true)
            val sharedSecret = keyAgreement.generateSecret()

            // Use the shared secret to derive an AES key
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(256) // AES-256
            val secretKey: SecretKey = keyGenerator.generateKey()

            // Encrypt the plain text using AES
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv // Initialization vector
            val encryptedText = cipher.doFinal(plainText.toByteArray())

            // Combine IV and encrypted text for the final output
            val combined = iv + encryptedText
            val encryptedBase64 = Base64.encodeToString(combined, Base64.DEFAULT)

            Log.d("KeyPairUtils", "Encrypted Text: $encryptedBase64")
            return encryptedBase64
        } catch (e: Exception) {
            Log.e("KeyPairUtils", "Error encrypting text", e)
            return null
        }
    }

    fun clearKeyFromKeystore(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
                Log.d("Keystore", "Key with alias '$KEY_ALIAS' deleted successfully.")
                true
            } else {
                Log.d("Keystore", "Key with alias '$KEY_ALIAS' does not exist.")
                false
            }
        } catch (e: Exception) {
            Log.e("Keystore", "Error clearing key from Keystore", e)
            false
        }
    }

    private fun generateRecipientECCKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        return keyPairGenerator.generateKeyPair()
    }
}
