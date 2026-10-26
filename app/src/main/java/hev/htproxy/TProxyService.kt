package hev.htproxy

class TProxyService {
    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyStartService(p0: String, p1: Int): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyStopService(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyIsRunning(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyGetStats(): LongArray
    }
}
