package android.util

object Log {
    const val VERBOSE = 2; const val DEBUG = 3; const val INFO = 4; const val WARN = 5; const val ERROR = 6
    private fun p(l: String, tag: String?, msg: String?): Int { System.err.println("$l/$tag: $msg"); return 0 }
    @JvmStatic fun v(tag: String?, msg: String?) = p("V", tag, msg)
    @JvmStatic fun d(tag: String?, msg: String?) = p("D", tag, msg)
    @JvmStatic fun i(tag: String?, msg: String?) = p("I", tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?) = p("W", tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?) = p("E", tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, t: Throwable?) = p("E", tag, "$msg ${t?.message}")
    @JvmStatic fun w(tag: String?, t: Throwable?) = p("W", tag, t?.message)
}

object Base64 {
    const val DEFAULT = 0; const val NO_PADDING = 1; const val NO_WRAP = 2; const val CRLF = 4; const val URL_SAFE = 8
    @JvmStatic fun encodeToString(input: ByteArray, flags: Int): String {
        var e = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) e = e.withoutPadding()
        return e.encodeToString(input)
    }
    @JvmStatic fun encode(input: ByteArray, flags: Int): ByteArray = encodeToString(input, flags).toByteArray()
    @JvmStatic fun decode(input: String, flags: Int): ByteArray {
        val s = input.filterNot { it.isWhitespace() }
        return (if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getMimeDecoder()).decode(s)
    }
    @JvmStatic fun decode(input: ByteArray, flags: Int): ByteArray = decode(String(input), flags)
}