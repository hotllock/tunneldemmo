package com.tunnel.demo.tunneldemo.cert

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.security.KeyChain
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.security.auth.x500.X500Principal

object CertificateManager {

    private const val KEYSTORE_ALIAS = "netpeeker_root_ca"
    private const val KEYSTORE_TYPE = "BKS"
    private const val KEYSTORE_PASSWORD = "netpeeker_keystore"
    private const val CA_CN = "CN=NetPeeker Root CA, O=NetPeeker, C=US"
    private const val ROOT_CA_FILENAME = "NetPeeker-RootCA.crt"
    private const val ROOT_CA_PEM_FILENAME = "NetPeeker-RootCA.pem"
    private const val VALIDITY_YEARS = 10

    private var isInitialized = false

    data class CertificateBundle(
        val certificate: X509Certificate,
        val keyPair: KeyPair,
        val derBytes: ByteArray,
        val pemBytes: String
    )

    fun initialize(context: Context): Boolean {
        if (isInitialized) return true
        return try {
            java.security.Security.removeProvider("BC")
            val bcProvider = org.bouncycastle.jce.provider.BouncyCastleProvider()
            java.security.Security.addProvider(bcProvider)
            isInitialized = true
            true
        } catch (e: Exception) {
            false
        }
    }

    fun generateRootCA(context: Context): CertificateBundle? {
        try {
            initialize(context)

            val keyPair = generateRsaKeyPair()
            val certificate = createCACertificate(keyPair)

            val derBytes = certificate.encoded
            val pemBytes = convertToPem(certificate)

            saveToDownloads(context, ROOT_CA_FILENAME, derBytes)
            saveToDownloads(context, ROOT_CA_PEM_FILENAME, pemBytes.toByteArray())

            storeInKeyStore(context, keyPair, certificate)

            return CertificateBundle(
                certificate = certificate,
                keyPair = keyPair,
                derBytes = derBytes,
                pemBytes = pemBytes
            )
        } catch (e: Exception) {
            return null
        }
    }

    fun installCertificate(context: Context) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val certFile = File(downloadsDir, ROOT_CA_FILENAME)
        if (!certFile.exists()) {
            generateRootCA(context)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                val intent = KeyChain.createInstallIntent().apply {
                    putExtra(KeyChain.EXTRA_CERTIFICATE, certFile.readBytes())
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (_: Exception) {}
    }

    fun isRootCAInstalled(context: Context): Boolean {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.containsAlias(KEYSTORE_ALIAS)
        } catch (_: Exception) {
            false
        }
    }

    fun generateServerCert(hostname: String): CertificateBundle? {
        try {
            val keyStore = loadKeyStore()
            val caCert = keyStore.getCertificate(KEYSTORE_ALIAS) as? X509Certificate ?: return null
            val caKey = keyStore.getKey(KEYSTORE_ALIAS, KEYSTORE_PASSWORD.toCharArray()) ?: return null

            val keyPair = generateRsaKeyPair(2048)

            val serverCert = createServerCertificate(
                hostname = hostname,
                serverKeyPair = keyPair,
                caCert = caCert,
                caPrivateKey = caKey as java.security.PrivateKey
            )

            val derBytes = serverCert.encoded
            val pemBytes = convertToPem(serverCert)

            return CertificateBundle(
                certificate = serverCert,
                keyPair = keyPair,
                derBytes = derBytes,
                pemBytes = pemBytes
            )
        } catch (e: Exception) {
            return null
        }
    }

    fun getCACertificate(context: Context): X509Certificate? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.getCertificate(KEYSTORE_ALIAS) as? X509Certificate
        } catch (_: Exception) {
            null
        }
    }

    private fun generateRsaKeyPair(keySize: Int = 2048): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA", "BC")
        generator.initialize(keySize, SecureRandom())
        return generator.generateKeyPair()
    }

    private fun createCACertificate(keyPair: KeyPair): X509Certificate {
        val subject = X500Principal(CA_CN)
        val serial = BigInteger(64, SecureRandom())

        val calendar = Calendar.getInstance()
        val notBefore = calendar.time
        calendar.add(Calendar.YEAR, VALIDITY_YEARS)
        val notAfter = calendar.time

        val certBuilder = org.bouncycastle.asn1.x500.X500NameBuilder(
            org.bouncycastle.asn1.x500.style.RFC4519Style.INSTANCE
        ).apply {
            addRDN(org.bouncycastle.asn1.x500.style.RFC4519Style.cn, "NetPeeker Root CA")
            addRDN(org.bouncycastle.asn1.x500.style.RFC4519Style.o, "NetPeeker")
            addRDN(org.bouncycastle.asn1.x500.style.RFC4519Style.c, "US")
        }

        val issuerName = certBuilder.build()
        val subjectName = certBuilder.build()

        val certGen = org.bouncycastle.cert.X509v3CertificateBuilder(
            issuerName,
            serial,
            notBefore,
            notAfter,
            subjectName,
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        )

        // Basic Constraints: CA=true
        certGen.addExtension(
            org.bouncycastle.asn1.x509.Extension.basicConstraints,
            true,
            org.bouncycastle.asn1.x509.BasicConstraints(true)
        )

        // Key Usage: keyCertSign, cRLSign
        certGen.addExtension(
            org.bouncycastle.asn1.x509.Extension.keyUsage,
            true,
            org.bouncycastle.asn1.x509.KeyUsage(
                org.bouncycastle.asn1.x509.KeyUsage.keyCertSign or
                        org.bouncycastle.asn1.x509.KeyUsage.cRLSign
            )
        )

        // Subject Key Identifier
        certGen.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectKeyIdentifier,
            false,
            org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()
                .createSubjectKeyIdentifier(keyPair.public)
        )

        // Authority Key Identifier (self-signed, same as subject)
        certGen.addExtension(
            org.bouncycastle.asn1.x509.Extension.authorityKeyIdentifier,
            false,
            org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()
                .createAuthorityKeyIdentifier(keyPair.public)
        )

        val certHolder = certGen.build(
            org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider("BC")
                .build(keyPair.private)
        )

        return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder)
    }

    private fun createServerCertificate(
        hostname: String,
        serverKeyPair: KeyPair,
        caCert: X509Certificate,
        caPrivateKey: java.security.PrivateKey
    ): X509Certificate {
        val serial = BigInteger(64, SecureRandom())

        val calendar = Calendar.getInstance()
        val notBefore = calendar.time
        calendar.add(Calendar.YEAR, 2)
        val notAfter = calendar.time

        val issuerBuilder = org.bouncycastle.asn1.x500.X500NameBuilder(
            org.bouncycastle.asn1.x500.style.RFC4519Style.INSTANCE
        ).apply {
            addRDN(org.bouncycastle.asn1.x500.style.RFC4519Style.cn, "NetPeeker Root CA")
            addRDN(org.bouncycastle.asn1.x500.style.RFC4519Style.o, "NetPeeker")
            addRDN(org.bouncycastle.asn1.x500.style.RFC4519Style.c, "US")
        }

        val subjectBuilder = org.bouncycastle.asn1.x500.X500NameBuilder(
            org.bouncycastle.asn1.x500.style.RFC4519Style.INSTANCE
        ).apply {
            addRDN(org.bouncycastle.asn1.x500.style.RFC4519Style.cn, hostname)
            addRDN(org.bouncycastle.asn1.x500.style.RFC4519Style.o, "NetPeeker")
        }

        val certGen = org.bouncycastle.cert.X509v3CertificateBuilder(
            issuerBuilder.build(),
            serial,
            notBefore,
            notAfter,
            subjectBuilder.build(),
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(serverKeyPair.public.encoded)
        )

        // Basic Constraints: CA=false
        certGen.addExtension(
            org.bouncycastle.asn1.x509.Extension.basicConstraints,
            true,
            org.bouncycastle.asn1.x509.BasicConstraints(false)
        )

        // Key Usage: digitalSignature, keyEncipherment
        certGen.addExtension(
            org.bouncycastle.asn1.x509.Extension.keyUsage,
            true,
            org.bouncycastle.asn1.x509.KeyUsage(
                org.bouncycastle.asn1.x509.KeyUsage.digitalSignature or
                        org.bouncycastle.asn1.x509.KeyUsage.keyEncipherment
            )
        )

        // Extended Key Usage: serverAuth
        certGen.addExtension(
            org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,
            false,
            org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth
            )
        )

        // Subject Alternative Name
        val subjectAltNames = org.bouncycastle.asn1.x509.GeneralNames(
            arrayOf(
                org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.dNSName, hostname
                )
            )
        )
        certGen.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
            false,
            subjectAltNames
        )

        // Subject Key Identifier
        certGen.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectKeyIdentifier,
            false,
            org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()
                .createSubjectKeyIdentifier(serverKeyPair.public)
        )

        // Authority Key Identifier
        certGen.addExtension(
            org.bouncycastle.asn1.x509.Extension.authorityKeyIdentifier,
            false,
            org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()
                .createAuthorityKeyIdentifier(caCert.publicKey)
        )

        val certHolder = certGen.build(
            org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider("BC")
                .build(caPrivateKey)
        )

        return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder)
    }

    private fun saveToDownloads(context: Context, filename: String, data: ByteArray) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val file = File(downloadsDir, filename)
            FileOutputStream(file).use { fos ->
                fos.write(data)
                fos.flush()
            }
        } catch (_: Exception) {}
    }

    private fun storeInKeyStore(context: Context, keyPair: KeyPair, certificate: X509Certificate) {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            keyStore.setKeyEntry(
                KEYSTORE_ALIAS,
                keyPair.private,
                null,
                arrayOf(certificate)
            )

            val storeFile = File(context.filesDir, "netpeeker_keystore.bks")
            val bks = KeyStore.getInstance(KEYSTORE_TYPE, "BC")
            bks.load(null, KEYSTORE_PASSWORD.toCharArray())
            bks.setKeyEntry(
                KEYSTORE_ALIAS,
                keyPair.private,
                KEYSTORE_PASSWORD.toCharArray(),
                arrayOf(certificate)
            )
            FileOutputStream(storeFile).use { fos ->
                bks.store(fos, KEYSTORE_PASSWORD.toCharArray())
            }
        } catch (_: Exception) {}
    }

    private fun loadKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE, "BC")
        keyStore.load(null, KEYSTORE_PASSWORD.toCharArray())
        return keyStore
    }

    private fun convertToPem(certificate: X509Certificate): String {
        val pemWriter = java.io.StringWriter()
        val jcePemWriter = org.bouncycastle.openssl.jcajce.JcaPEMWriter(pemWriter)
        jcePemWriter.writeObject(certificate)
        jcePemWriter.flush()
        jcePemWriter.close()
        return pemWriter.toString()
    }

    fun deleteRootCA(context: Context): Boolean {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(KEYSTORE_ALIAS)

            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            File(downloadsDir, ROOT_CA_FILENAME).delete()
            File(downloadsDir, ROOT_CA_PEM_FILENAME).delete()
            true
        } catch (_: Exception) {
            false
        }
    }
}
