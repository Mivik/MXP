package com.mivik.mxp

import android.annotation.SuppressLint
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.*
import java.lang.RuntimeException

internal const val T = "MXP"

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
object MXP {
	val SELF_PACKAGE_NAME by lazy {
		Class.forName("com.mivik.mxp.Const").getField("SELF_PACKAGE_NAME").get(null) as String
	}

	const val AID_USER = 100000

	@SuppressLint("SdCardPath")
	private val DATA_DIR_PATH_PREFIX =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) "/data/user_de/0" else "/data/user/0"

	val baseDirectory by lazy {
		File(DATA_DIR_PATH_PREFIX, SELF_PACKAGE_NAME).apply {
			if (isFile) delete()
			mkdirs()
			setFilePermissions(absolutePath, 511, -1, -1)
		}.absolutePath
	}

	val databaseFile by lazy {
		File(baseDirectory, "mdb").apply {
			if (!exists()) createNewFile()
			setFilePermissions(absolutePath, 511, -1, -1)
		}
	}

	private var tmpApkFile: File? = null
	val apkFile: File?
		get() {
			if (tmpApkFile == null || (!tmpApkFile!!.exists())) tmpApkFile = getApkFile(SELF_PACKAGE_NAME)
			return tmpApkFile
		}

	private val methodSetPermissions by lazy {
		Class.forName("android.os.FileUtils")
			.getDeclaredMethod("setPermissions", String::class.java, Int::class.java, Int::class.java, Int::class.java)
			.apply { isAccessible = true }
	}

	private val methodGetFileContext by lazy {
		Class.forName("android.os.SELinux")
			.getDeclaredMethod("getFileContext", String::class.java)
			.apply { isAccessible = true }
	}

	fun getFileContext(path: String): String? {
		return methodGetFileContext.invoke(null, path) as String?
	}

	fun getExpectedFileContext(path: String): String? {
		val userId = Process.myUid() / AID_USER
		val ori = getFileContext(path) ?: return null
		return "${ori.substring(
			0,
			ori.lastIndexOf(':')
		)}:c${(userId and 0xff) + 512},c${((userId shr 8) and 0xff) + 768}"
	}

	fun isActivated(): Boolean {
		val expected = getExpectedFileContext(baseDirectory)
		return getFileContext(databaseFile.absolutePath) == expected
				&& getFileContext(baseDirectory) == expected
	}

	fun activate(useSu: Boolean = true) {
		if (isActivated()) return
		try {
			val arg =
				mutableListOf("chcon", getExpectedFileContext(baseDirectory), baseDirectory, databaseFile.absolutePath)
			if (useSu) arg.addAll(0, listOf("su", "-c"))
			Runtime.getRuntime().exec(arg.toTypedArray()).waitFor()
		} catch (t: Throwable) {
			Log.e(T, "Failed to activate MXP", t)
			throw RuntimeException(t)
		}
	}

	val database by lazy {
		MDatabase(object : IOProvider<FileIOProvider.Stamp> {
			override val stamp: FileIOProvider.Stamp
				get() = FileIOProvider.Stamp(databaseFile)

			override fun obtainInput(): InputStream = databaseFile.inputStream()

			override fun obtainOutput(): OutputStream {
				if (!databaseFile.exists()) databaseFile.createNewFile()
				if (databaseFile.isFile) setFilePermissions(databaseFile.absolutePath, 511, -1, -1)
				return databaseFile.outputStream()
			}
		})
	}

	fun setFilePermissions(path: String, mode: Int, uid: Int, gid: Int) {
		methodSetPermissions.invoke(null, path, mode, uid, gid)
	}

	fun getApkFile(packageName: String): File? {
		try {
			val pro = Runtime.getRuntime().exec(arrayOf("pm", "list", "package", "-f"))
			val reader = BufferedReader(InputStreamReader(pro.inputStream))
			var line: String
			while (reader.readLine().also { line = it } != null) {
				if (line.isEmpty()) continue
				val ind = line.lastIndexOf('=')
				val cur = line.substring(ind + 1)
				if (cur != packageName) continue
				return File(line.substring(8, ind))
			}
		} catch (t: Throwable) {
			Log.e(T, "Failed to get apk file", t)
		}
		return null
	}

	fun unsealHiddenApi() {
		if (Build.VERSION.SDK_INT < 28) return
		try {
			val forName =
				Class::class.java.getDeclaredMethod("forName", String::class.java)
			val vmRuntimeClass =
				forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
			val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
			val setHiddenApiExemptions = vmRuntimeClass.getDeclaredMethod(
				"setHiddenApiExemptions",
				Array<String>::class.java
			)
			val sVmRuntime = getRuntime.invoke(null)
			setHiddenApiExemptions.invoke(sVmRuntime, arrayOf("L"))
			Log.i(T, "Unsealed Hidden API")
		} catch (t: Throwable) {
			Log.e(T, "Failed to unseal Hidden API", t)
		}
	}
}