package io.kitsuri.pelauncher.Launcher

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Unified manager for Minecraft package resolution, library loading, and asset management
 */
class GamePackageManager private constructor(private val context: Context) {

    private val packageContext: Context
    private val assetManager: AssetManager
    private val nativeLibDir: String

    private val knownPackages = arrayOf(
        "com.mojang.minecraftpe",
        "com.mojang.minecraftpe.beta",
        "com.mojang.minecraftpe.preview"
    )

    private val requiredLibs = arrayOf(
        "libc++_shared.so",
        "libfmod.so",
        "libMediaDecoders_Android.so",
        "libmaesdk.so",
        "libHttpClient.Android.so",
        "libminecraftpe.so"
    )

    private val systemLoadLibs = arrayOf(
        "libpairipcore.so",
        "libPlayFabMultiplayer.so"
    )

    init {
        val packageName = detectGamePackage()
            ?: throw IllegalStateException("Minecraft not found")

        packageContext = context.createPackageContext(
            packageName,
            Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
        )

        nativeLibDir = resolveNativeLibDir()
        extractLibraries()

        assetManager = createAssetManager()
    }

    private fun detectGamePackage(): String? {
        return knownPackages.firstOrNull { isPackageInstalled(it) }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun resolveNativeLibDir(): String {
        val appInfo = packageContext.applicationInfo

        return if (appInfo.splitPublicSourceDirs?.isNotEmpty() == true) {
            // App bundle format - extract to cache
            val cacheLibDir = File(context.cacheDir, "lib/${getDeviceAbi()}")
            cacheLibDir.mkdirs()
            cacheLibDir.absolutePath
        } else {
            // Standard APK
            appInfo.nativeLibraryDir
        }
    }

    private fun getDeviceAbi(): String {
        return Build.SUPPORTED_64_BIT_ABIS.firstOrNull {
            it.contains("arm64-v8a") || it.contains("x86_64")
        } ?: Build.SUPPORTED_32_BIT_ABIS.firstOrNull {
            it.contains("armeabi-v7a") || it.contains("x86")
        } ?: Build.CPU_ABI
    }

    private fun extractLibraries() {
        val appInfo = packageContext.applicationInfo
        val outputDir = File(nativeLibDir)

        // Try native lib dir first
        if (File(appInfo.nativeLibraryDir).exists()) {
            copyFromNativeDir(appInfo.nativeLibraryDir, outputDir)
        }

        // Extract from APKs if needed
        val apkPaths = mutableListOf<String>()
        appInfo.sourceDir?.let { apkPaths.add(it) }
        appInfo.splitPublicSourceDirs?.let { apkPaths.addAll(it) }

        apkPaths.forEach { extractFromApk(it, outputDir) }

        verifyLibraries(outputDir)
    }

    private fun copyFromNativeDir(sourceDir: String, destDir: File) {
        val source = File(sourceDir)
        if (!source.exists()) return

        requiredLibs.forEach { lib ->
            val srcFile = File(source, lib)
            val dstFile = File(destDir, lib)

            if (srcFile.exists() && srcFile.length() > 0) {
                try {
                    srcFile.copyTo(dstFile, overwrite = true)
                    dstFile.setReadable(true)
                    dstFile.setExecutable(true)
                    Log.d(TAG, "Copied $lib")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to copy $lib: ${e.message}")
                }
            }
        }
    }

    private fun extractFromApk(apkPath: String, outputDir: File) {
        if (!apkPath.contains("arm") && !apkPath.contains("x86") &&
            !apkPath.contains("base.apk")) return

        try {
            ZipFile(apkPath).use { zip ->
                val abi = "lib/${getDeviceAbi()}"

                requiredLibs.forEach { lib ->
                    val entry = zip.getEntry("$abi/$lib") ?: return@forEach
                    val output = File(outputDir, lib)

                    if (output.exists() && output.length() > 0) return@forEach

                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { out ->
                            input.copyTo(out)
                        }
                    }

                    output.setReadable(true)
                    output.setExecutable(true)
                    Log.d(TAG, "Extracted $lib from ${File(apkPath).name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract from $apkPath: ${e.message}")
        }
    }

    private fun verifyLibraries(dir: File) {
        val missing = requiredLibs.filterNot {
            File(dir, it).let { f -> f.exists() && f.length() > 0 }
        }

        if (missing.isNotEmpty()) {
            Log.w(TAG, "Missing libraries: ${missing.joinToString()}")
        } else {
            Log.i(TAG, "All libraries verified")
        }
    }

    private fun createAssetManager(): AssetManager {
        val assets = AssetManager::class.java.newInstance()
        val addAssetPathMethod = AssetManager::class.java.getMethod(
            "addAssetPath",
            String::class.java
        )

        // Add game assets
        addAssetPathMethod.invoke(assets, packageContext.packageResourcePath)

        // Add split APK assets if present
        val basePath = packageContext.packageResourcePath
        val splitPath = basePath.replace("base.apk", "split_install_pack.apk")
        if (File(splitPath).exists()) {
            addAssetPathMethod.invoke(assets, splitPath)
        }

        // Add launcher assets
        addAssetPathMethod.invoke(assets, context.packageResourcePath)

        return assets
    }

    /**
     * Load a specific library by name
     */
    fun loadLibrary(name: String): Boolean {
        val libFile = File(nativeLibDir, if (name.startsWith("lib")) name else "lib$name.so")
        val cleanName = name.removePrefix("lib").removeSuffix(".so")

        return try {
            if (systemLoadLibs.contains(libFile.name)) {
                System.loadLibrary(cleanName)
                Log.d(TAG, "Loaded $name as system library")
            } else if (libFile.exists()) {
                System.load(libFile.absolutePath)
                Log.d(TAG, "Loaded $name")
            } else {
                System.loadLibrary(cleanName)
                Log.d(TAG, "Loaded $name as system library (fallback)")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $name: ${e.message}")
            false
        }
    }

    /**
     * Load all required libraries in order
     */
    fun loadAllLibraries() {
        requiredLibs.forEach { lib ->
            loadLibrary(lib)
        }
    }

    /**
     * Get the asset manager with game and launcher assets
     */
    fun getAssets(): AssetManager = assetManager

    /**
     * Get the game package context
     */
    fun getPackageContext(): Context = packageContext

    /**
     * Get version information
     */
    fun getVersionName(): String? {
        return try {
            context.packageManager.getPackageInfo(
                packageContext.packageName,
                0
            ).versionName
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "GamePackageManager"

        @Volatile
        private var instance: GamePackageManager? = null

        fun getInstance(context: Context): GamePackageManager {
            return instance ?: synchronized(this) {
                instance ?: GamePackageManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun isInitialized() = instance != null
    }
}