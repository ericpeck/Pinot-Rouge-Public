package android.net

/**
 * Non-null [Uri] for JVM unit tests.
 *
 * The compile SDK's `android.jar` stubs throw on [Uri.parse] and initialize
 * [Uri.EMPTY] to null, which is why [com.pinotrouge.messaging.sms.IncomingMessagePipelineTest]
 * previously used `WriteResult.Success(uri = null)` as its success case. This
 * lives in `android.net` so it can reach Uri's package-private constructor.
 */
object InsertedSmsUri : Uri() {
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun getScheme(): String = "content"
    override fun getSchemeSpecificPart(): String = "//sms/1"
    override fun getEncodedSchemeSpecificPart(): String = "//sms/1"
    override fun getAuthority(): String = "sms"
    override fun getEncodedAuthority(): String = "sms"
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String = "sms"
    override fun getPort(): Int = -1
    override fun getPath(): String = "/1"
    override fun getEncodedPath(): String = "/1"
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): List<String> = listOf("1")
    override fun getLastPathSegment(): String = "1"
    override fun toString(): String = "content://sms/1"
    override fun buildUpon(): Builder = throw UnsupportedOperationException("test uri")
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: android.os.Parcel, flags: Int) = Unit
}
