package com.mivik.mxp

import android.util.Log
import dalvik.system.PathClassLoader
import de.robv.android.xposed.IXposedHookInitPackageResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_InitPackageResources.InitPackageResourcesParam
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.*
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val T = "MXP"

class MXPModule private constructor(private val apkFile: File) {
	private var lastModifiedTime: Long = 0
	private var lastSize: Long = 0
	private val hookLoadPackages = mutableListOf<IXposedHookLoadPackage>()
	private val hookInitPackageResources = mutableListOf<IXposedHookInitPackageResources>()

	val hasChanged: Boolean
		get() = synchronized(this) {
			return if (lastSize != apkFile.length()) true else lastModifiedTime != apkFile.lastModified()
		}

	fun ensureLoaded(): Boolean {
		if (hasChanged) {
			Log.i(T, "Apk file changed, reload")
			load()
			return true
		}
		return false
	}

	private fun sync() {
		synchronized(this) {
			lastModifiedTime = apkFile.lastModified()
			lastSize = apkFile.length()
		}
	}

	internal fun handleLoadPackage(param: LoadPackageParam?) {
		for (one in hookLoadPackages) {
			try {
				one.handleLoadPackage(param)
			} catch (t: Throwable) {
				Log.e(T, "Error occurred in module ${one.javaClass.name}", t)
			}
		}
	}

	internal fun handleInitPackageResources(param: InitPackageResourcesParam?) {
		for (one in hookInitPackageResources) {
			try {
				one.handleInitPackageResources(param)
			} catch (t: Throwable) {
				Log.e(T, "Error occurred in module ${one.javaClass.name}", t)
			}
		}
	}

	private fun load(): Boolean {
		hookLoadPackages.clear()
		hookInitPackageResources.clear()
		var input: InputStream? = null
		var file: ZipFile? = null
		val entry: ZipEntry?
		try {
			file = ZipFile(apkFile)
			entry = file.getEntry("assets/mxp_init")
			if (entry == null) {
				Log.e(T, "assets/xposed_init is not found in $apkFile")
				return false
			}
			input = file.getInputStream(entry)
		} catch (e: IOException) {
			Log.e(T, "Failed to load module from $apkFile", e)
			try {
				input?.close()
				file?.close()
			} catch (ee: IOException) {
				Log.e(T, "Failed to close", ee)
			}
			return false
		}
		val loader: ClassLoader = PathClassLoader(apkFile.absolutePath, XposedBridge.BOOTCLASSLOADER)
		var reader: BufferedReader? = null
		try {
			reader = BufferedReader(InputStreamReader(input!!))
			var line: String?
			while (true) {
				line = reader.readLine()
				line ?: break
				line = line.trim { it <= ' ' }
				if (line.isEmpty() || line.startsWith("#")) continue
				try {
					Log.v(T, "Loading class $line")
					val moduleClass = loader.loadClass(line)
					when (val module = moduleClass.newInstance()) {
						is IXposedHookLoadPackage -> hookLoadPackages.add(module)
						is IXposedHookInitPackageResources -> hookInitPackageResources.add(module)
						else -> Log.e(T, "Unsupported class: $moduleClass")
					}
				} catch (t: Throwable) {
					Log.e(T, "Failed to load class $line", t)
				}
			}
		} catch (e: IOException) {
			Log.e(T, "Failed to read xposed_init file")
			return false
		} finally {
			try {
				reader?.close()
				input.close()
				file.close()
			} catch (e: IOException) {
				Log.e(T, "Failed to close file")
			}
		}
		sync()
		return true
	}

	companion object {
		private var INSTANCE: MXPModule? = null

		@Synchronized
		fun getInstance(file: File?): MXPModule? {
			file ?: return null
			if (INSTANCE == null) INSTANCE = MXPModule(file)
			return INSTANCE
		}
	}

	init {
		load()
	}
}