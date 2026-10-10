package app.onestream.plugins

import com.googlecode.d2j.dex.Dex2jar
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.Type
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Turns an Android CloudStream plugin (`.cs3`: a zip with classes.dex and manifest.json) into a plain JVM plugin jar.
 * Best effort: the plugin runs against the stand-ins in `compat/`, so anything that needs real Android APIs fails when it runs.
 * Conversion does not execute the plugin; callers must get the user's approval and pin the result before it is loaded.
 */
object Cs3 {
    fun convert(cs3: ByteArray, out: File) {
        val tmp = Files.createTempDirectory("cs3").toFile()
        try {
            var manifest: String? = null
            ZipInputStream(ByteArrayInputStream(cs3)).use { z ->
                generateSequence { z.nextEntry }.forEach { e ->
                    when {
                        e.name == "manifest.json" -> manifest = z.readBytes().decodeToString()
                        Regex("classes\\d*\\.dex").matches(e.name) -> File(tmp, e.name).writeBytes(z.readBytes())
                    }
                }
            }
            val m = Json.parseToJsonElement(manifest ?: throw RepoException("Not a .cs3 plugin: manifest.json missing")) as JsonObject
            val cls = m["pluginClassName"]?.jsonPrimitive?.contentOrNull ?: throw RepoException("Not a .cs3 plugin: pluginClassName missing")
            val pm = PluginManifest(m["name"]?.jsonPrimitive?.contentOrNull ?: cls, cls, m["version"]?.jsonPrimitive?.intOrNull ?: 1, android = true)
            val dexes = tmp.listFiles { f -> f.extension == "dex" }.orEmpty().sortedBy { it.name }
            if (dexes.isEmpty()) throw RepoException("Not a .cs3 plugin: no classes.dex")

            val entries = LinkedHashMap<String, ByteArray>()
            for (dex in dexes) {
                val jar = File(tmp, dex.name + ".jar")
                Dex2jar.from(dex).to(jar.toPath())
                ZipFile(jar).use { zf ->
                    zf.entries().asSequence().filter { !it.isDirectory && it.name !in entries }.forEach { e ->
                        entries[e.name] = zf.getInputStream(e).use { it.readBytes() }
                    }
                }
            }
            restoreLocalClasses(entries)
            stubMissingAndroid(entries)
            ZipOutputStream(out.outputStream().buffered()).use { zo ->
                entries.forEach { (name, bytes) -> zo.putNextEntry(ZipEntry(name)); zo.write(bytes); zo.closeEntry() }
                zo.putNextEntry(ZipEntry("manifest.json"))
                zo.write(Json.encodeToString(pm).toByteArray()); zo.closeEntry()
            }
        } catch (e: RepoException) { out.delete(); throw e } catch (e: OutOfMemoryError) { out.delete(); throw e } catch (e: Throwable) {
            out.delete(); throw RepoException("Could not convert the Android plugin: ${e.message ?: e.javaClass.simpleName}")
        } finally { tmp.deleteRecursively() }
    }

    private val classExists = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Converts [cs3] and says why it cannot run here, or null if it looks fine. A plugin that calls CloudStream app classes
     * this port does not have (e.g. `CloudStreamApp`) fails to load, so it is flagged. Nothing is executed.
     */
    fun check(cs3: ByteArray): String? {
        val tmp = File.createTempFile("cs3check", ".jar")
        try {
            convert(cs3, tmp)
            val have = HashSet<String>()
            val refs = HashSet<String>()
            ZipFile(tmp).use { zf ->
                for (e in zf.entries().asSequence().filter { it.name.endsWith(".class") }) {
                    have += e.name.removeSuffix(".class")
                    val n = ClassNode().also { ClassReader(zf.getInputStream(e).readBytes()).accept(it, ClassReader.SKIP_DEBUG) }
                    n.superName?.let(refs::add); refs += n.interfaces
                    for (m in n.methods) for (i in m.instructions) when (i) {
                        is MethodInsnNode -> refs += i.owner
                        is FieldInsnNode -> refs += i.owner
                        is TypeInsnNode -> refs += i.desc
                    }
                }
            }
            val loader = Cs3::class.java.classLoader
            val missing = refs.filter { it.startsWith("com/lagradost/") && it !in have }.sorted().firstOrNull { n ->
                !classExists.getOrPut(n) { try { Class.forName(n.replace('/', '.'), false, loader); true } catch (_: Throwable) { false } }
            }
            return missing?.let { "Needs ${it.substringAfterLast('/')}, which only exists in the Android app" }
        } catch (e: RepoException) { return e.message } finally { tmp.delete() }
    }

    /**
     * Android classes the plugin touches but this app does not provide (dialogs, views, drawables...) get generated stubs whose
     * methods return null/0, so linking succeeds and UI-only code paths quietly do nothing instead of killing `load()`.
     * Needs bytecode verification off for plugin classes (see -XX:-BytecodeVerificationRemote in the app's JVM args).
     */
    private fun stubMissingAndroid(entries: MutableMap<String, ByteArray>) {
        class Stub { var iface = false; val methods = LinkedHashSet<Triple<String, String, Boolean>>(); val fields = LinkedHashSet<Triple<String, String, Boolean>>() }
        val stubs = LinkedHashMap<String, Stub>()
        val own = entries.keys
        fun stub(o: String): Stub? =
            if ((o.startsWith("android/") || o.startsWith("androidx/")) && "$o.class" !in own && Cs3::class.java.classLoader.getResource("$o.class") == null) stubs.getOrPut(o) { Stub() } else null
        for ((path, bytes) in entries.toList()) {
            if (!path.endsWith(".class")) continue
            val n = ClassNode().also { ClassReader(bytes).accept(it, 0) }
            n.superName?.let { stub(it) }
            n.interfaces.forEach { stub(it)?.iface = true }
            for (m in n.methods) m.tryCatchBlocks?.forEach { b -> b.type?.let { stub(it) } }
            for (m in n.methods) for (i in m.instructions) when (i) {
                is MethodInsnNode -> stub(i.owner)?.let { if (i.opcode == Opcodes.INVOKEINTERFACE) it.iface = true; it.methods += Triple(i.name, i.desc, i.opcode == Opcodes.INVOKESTATIC) }
                is FieldInsnNode -> stub(i.owner)?.fields?.add(Triple(i.name, i.desc, i.opcode == Opcodes.GETSTATIC || i.opcode == Opcodes.PUTSTATIC))
                is TypeInsnNode -> stub(i.desc)
            }
        }
        for ((name, s) in stubs) {
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
            cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC or if (s.iface) Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT else 0, name, null, "java/lang/Object", null)
            for ((f, d, st) in s.fields) cw.visitField(Opcodes.ACC_PUBLIC or if (st) Opcodes.ACC_STATIC else 0, f, d, null, null).visitEnd()
            for ((m, d, st) in s.methods) {
                if (s.iface) { if (!st && m != "<init>") cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, m, d, null, null).visitEnd(); continue }
                val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or if (st) Opcodes.ACC_STATIC else 0, m, d, null, null)
                mv.visitCode()
                if (m == "<init>") { mv.visitVarInsn(Opcodes.ALOAD, 0); mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); mv.visitInsn(Opcodes.RETURN) }
                else when (Type.getReturnType(d).sort) {
                    Type.VOID -> mv.visitInsn(Opcodes.RETURN)
                    Type.LONG -> { mv.visitInsn(Opcodes.LCONST_0); mv.visitInsn(Opcodes.LRETURN) }
                    Type.FLOAT -> { mv.visitInsn(Opcodes.FCONST_0); mv.visitInsn(Opcodes.FRETURN) }
                    Type.DOUBLE -> { mv.visitInsn(Opcodes.DCONST_0); mv.visitInsn(Opcodes.DRETURN) }
                    Type.OBJECT, Type.ARRAY -> { mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ARETURN) }
                    else -> { mv.visitInsn(Opcodes.ICONST_0); mv.visitInsn(Opcodes.IRETURN) }
                }
                mv.visitMaxs(0, 0); mv.visitEnd()
            }
            cw.visitEnd(); entries["$name.class"] = cw.toByteArray()
        }
    }

    /**
     * dex2jar loses the EnclosingMethod attribute of classes declared inside a function (`Outer$fn$Local`), and Kotlin
     * reflection (used by jackson-module-kotlin) then cannot resolve them. Put it back when the outer class has a method of that name.
     */
    private fun restoreLocalClasses(entries: MutableMap<String, ByteArray>) {
        val nodes = entries.filterKeys { it.endsWith(".class") }.mapValues { (_, b) -> ClassNode().also { ClassReader(b).accept(it, 0) } }
        val byName = nodes.values.associateBy { it.name }
        val fixed = HashSet<String>()
        for ((path, n) in nodes) {
            if (n.outerClass != null) continue
            val self = n.innerClasses.firstOrNull { it.name == n.name && it.outerName != null && it.innerName != null } ?: continue
            val outer = byName[self.outerName] ?: continue
            val rest = n.name.removePrefix(outer.name + "$")
            val fn = rest.removeSuffix("$" + self.innerName)
            if (fn == rest || fn.isEmpty() || fn.contains('$')) continue
            val m = outer.methods.firstOrNull { it.name == fn } ?: continue
            n.outerClass = outer.name; n.outerMethod = m.name; n.outerMethodDesc = m.desc
            self.outerName = null // a local class has no enclosing class entry
            fixed += n.name
        }
        // Every class that mentions a repaired one in InnerClasses must agree, or the JVM throws IncompatibleClassChangeError
        for ((path, n) in nodes) {
            var touched = n.name in fixed
            // dex2jar emits version 52 without StackMapTable frames, which the JVM rejects (VerifyError); version 49 uses the inference verifier
            if (n.version > Opcodes.V1_5) { n.version = Opcodes.V1_5; touched = true }
            // Android's dex has no '-' in method names, so calls to kotlin.Result's `constructor-impl` etc. arrive as `constructor_impl`
            for (m in n.methods) for (insn in m.instructions) if (insn is MethodInsnNode && insn.owner == "kotlin/Result" && "_impl" in insn.name) {
                insn.name = insn.name.replace("_impl", "-impl"); touched = true
            }
            for (ic in n.innerClasses) if (ic.name in fixed && ic.outerName != null) { ic.outerName = null; touched = true }
            if (touched) entries[path] = ClassWriter(ClassWriter.COMPUTE_MAXS).also { n.accept(it) }.toByteArray()
        }
    }
}