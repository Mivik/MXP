package com.mivik.mxp

import android.util.Log
import java.io.*
import java.util.*

private const val T = "MDatabase"

/**
 * 基于 [DataInput] 和 [DataOutput] 的二进制简单数据库，支持自动序列化/反序列化一个字符串为键值的 [Map
 * 支持的值类型详见 [MDatabase.Type]
 *
 * @param map 初始map
 * @param provider 提供输入输出流以及判断本地数据库是否更改的Stamp对象
 *
 * @author Mivik
 */
class MDatabase(val provider: IOProvider<*>, private val map: MutableMap<String, Any> = mutableMapOf()) {
	companion object {
		/**
		 * [MDatabase] 支持的数据类型，分别用 [Byte] 表示
		 */
		object Type {
			const val Byte: Byte = 0
			const val Short: Byte = 1
			const val Int: Byte = 2
			const val Long: Byte = 3
			const val Float: Byte = 4
			const val Double: Byte = 5
			const val Boolean: Byte = 6
			const val Char: Byte = 7
			const val String: Byte = 8
			const val List: Byte = 9
			const val Set: Byte = 10
			const val Map: Byte = 11
		}
	}

	/**
	 * 数据库是否已经加载
	 *
	 * 在从未加载或正在加载时为false
	 */
	var loaded: Boolean = false

	/**
	 * 上次加载时的数据库戳
	 */
	private var stamp: Any? = null
	private val lock = Object()

	/**
	 * 判断数据库文件是否已经有更改
	 */
	private val changed: Boolean
		get() = provider.stamp != stamp

	init {
		forceReload()
	}

	/**
	 * 如果更改，则重新加载数据库。非阻塞。
	 */
	fun reload(): Boolean {
		if (changed) {
			forceReload()
			return true
		}
		return false
	}

	/**
	 * 强制重新从本地加载。非阻塞。
	 */
	private fun forceReload() {
		synchronized(lock) {
			loaded = false
		}
		object : Thread("MSP-Load") {
			override fun run() {
				synchronized(lock) {
					load()
				}
			}
		}.start()
	}

	private fun load() {
		if (loaded) return
		try {
			DataInputStream(provider.obtainInput()).use { input ->
				sync()
				repeat(input.readInt()) {
					val key = input.readUTF()
					val value = readFrom(input)
					if (value != null) map[key] = value
				}
			}
		} catch (e: IOException) {
			Log.w(T, "Failed to load database", e)
		}
		loaded = true
		lock.notifyAll()
	}

	private fun sync() {
		stamp = provider.stamp
	}

	private fun waitTilLoaded() {
		while (!loaded) {
			lock.wait()
		}
	}

	fun remove(key: String): Any? {
		synchronized(lock) {
			waitTilLoaded()
		}
		return map.remove(key)
	}

	fun clear() {
		synchronized(lock) {
			waitTilLoaded()
		}
		map.clear()
	}

	inline fun <reified V> get(key: String, defaultValue: V): V = this[key] as? V ?: defaultValue

	inline fun <reified V : Any> getOrPut(key: String, defaultValue: V): V {
		val ret = this[key] as? V
		return ret ?: defaultValue.also { this[key] = it }
	}

	operator fun set(key: String, value: Any) {
		synchronized(lock) {
			waitTilLoaded()
		}
		map[key] = value
	}

	operator fun get(key: String): Any? {
		synchronized(lock) {
			waitTilLoaded()
		}
		return map[key]
	}

	/**
	 * 保存所作出的更改。非阻塞。
	 */
	fun apply() {
		object : Thread("MSP-Save") {
			override fun run() {
				commit()
			}
		}.start()
	}

	/**
	 * 保存所作出的更改。阻塞。
	 */
	fun commit(): Boolean {
		synchronized(lock) {
			waitTilLoaded()
			try {
				DataOutputStream(provider.obtainOutput()).use { output ->
					output.writeInt(map.size)
					for (entry in map) {
						output.writeUTF(entry.key)
						writeTo(output, entry.value)
					}
				}
			} catch (e: IOException) {
				e.printStackTrace()
				return false
			}
			sync()
			return true
		}
	}

	private fun readFrom(input: DataInput): Any? = with(Type) {
		when (input.readByte()) {
			Byte -> input.readByte()
			Short -> input.readShort()
			Int -> input.readInt()
			Long -> input.readLong()
			Float -> input.readFloat()
			Double -> input.readDouble()
			Boolean -> input.readBoolean()
			Char -> input.readChar()
			String -> input.readUTF()
			List -> mutableListOf<Any>().apply {
				repeat(input.readInt()) {
					val value = readFrom(input)
					if (value != null) this.add(value)
				}
			}
			Set -> mutableSetOf<Any>().apply {
				repeat(input.readInt()) {
					val value = readFrom(input)
					if (value != null) this.add(value)
				}
			}
			Map -> mutableMapOf<Any, Any>().apply {
				for (i in 1..input.readInt()) {
					val key = readFrom(input)
					val value = readFrom(input)
					if (key == null || value == null) continue
					this[key] = value
				}
			}
			else -> null
		}
	}

	private fun writeTo(output: DataOutput, value: Any?) {
		with(Type) {
			when (value) {
				is Byte -> {
					output.writeByte(Byte.toInt())
					output.writeByte(value.toInt())
				}
				is Short -> {
					output.writeByte(Short.toInt())
					output.writeShort(value.toInt())
				}
				is Int -> {
					output.writeByte(Int.toInt())
					output.writeInt(value)
				}
				is Long -> {
					output.writeByte(Long.toInt())
					output.writeLong(value)
				}
				is Float -> {
					output.writeByte(Float.toInt())
					output.writeFloat(value)
				}
				is Double -> {
					output.writeByte(Double.toInt())
					output.writeDouble(value)
				}
				is Boolean -> {
					output.writeByte(Boolean.toInt())
					output.writeBoolean(value)
				}
				is Char -> {
					output.writeByte(Char.toInt())
					output.writeChar(value.toInt())
				}
				is String -> {
					output.writeByte(String.toInt())
					output.writeUTF(value)
				}
				is List<*> -> {
					output.writeByte(List.toInt())
					output.writeInt(value.size)
					value.forEach { writeTo(output, it) }
				}
				is Set<*> -> {
					output.writeByte(Set.toInt())
					output.writeInt(value.size)
					value.forEach { writeTo(output, it) }
				}
				is Map<*, *> -> {
					output.writeByte(Map.toInt())
					output.writeInt(value.size)
					for (i in value) {
						writeTo(output, i.key)
						writeTo(output, i.value)
					}
				}
			}
		}
	}
}

/**
 * 用于向 [MDatabase] 提供输入流和输出流以及一个判断本地数据库是否更改的戳
 * 常见实现是 [FileIOProvider]
 *
 * @author Mivik
 */
interface IOProvider<T> {
	val stamp: T

	/**
	 * 创建输入流
	 */
	fun obtainInput(): InputStream

	/**
	 * 创建输出流
	 */
	fun obtainOutput(): OutputStream
}

/**
 * 基于文件的 [IOProvider] 实现
 *
 * @author Mivik
 */
class FileIOProvider(private val file: File) : IOProvider<FileIOProvider.Stamp> {
	override val stamp: Stamp
		get() = Stamp(file)

	override fun obtainInput(): InputStream = FileInputStream(file)

	override fun obtainOutput(): OutputStream = FileOutputStream(file)

	/**
	 * 文件戳，使用 [lastModified] 和 [size] 和两个值来判断文件是否更改
	 */
	class Stamp(val lastModified: Long, val size: Long) {
		constructor(file: File) : this(file.lastModified(), file.length())

		override fun equals(other: Any?): Boolean {
			other ?: return false
			if (other !is Stamp) return false
			return other.lastModified == lastModified && other.size == size
		}

		override fun hashCode(): Int {
			var result = lastModified.hashCode()
			result = 31 * result + size.hashCode()
			return result
		}
	}
}

fun MDatabase(file: File): MDatabase = MDatabase(provider = FileIOProvider(file))
fun MDatabase(path: String): MDatabase = MDatabase(File(path))
