package app.multiwifi

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal SOCKS5 (CONNECT only) server on 127.0.0.1.
 * Every new connection is dialed through the least busy [NetworkPool.Link],
 * and DNS is resolved on that same network.
 */
class Socks5Proxy(private val pool: NetworkPool, private val port: Int = 1080) {

    private val exec = Executors.newCachedThreadPool()
    @Volatile private var server: ServerSocket? = null

    fun start() {
        val s = ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        exec.execute {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: IOException) { break }
                exec.execute { handle(client) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        exec.shutdownNow()
    }

    private fun handle(client: Socket) {
        var link: NetworkPool.Link? = null
        var remote: Socket? = null
        try {
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()

            // Greeting: accept "no auth" only.
            if (input.readUnsignedByte() != 5) return
            input.readFully(ByteArray(input.readUnsignedByte()))
            output.write(byteArrayOf(5, 0)); output.flush()

            // Request
            if (input.readUnsignedByte() != 5) return
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // reserved
            val host = when (input.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
                3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
                4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
                else -> { reply(output, 8); return }
            }
            val port = input.readUnsignedShort()
            if (cmd != 1) { reply(output, 7); return }

            link = pool.pick()
            if (link == null) { reply(output, 3); return }

            remote = try {
                val address = link.network.getByName(host)
                link.network.socketFactory.createSocket().also {
                    it.connect(InetSocketAddress(address, port), 10_000)
                }
            } catch (e: IOException) {
                reply(output, 4); return
            }

            reply(output, 0)

            val r = remote
            exec.execute { pipe(r.getInputStream(), client.getOutputStream()); closeQuietly(r, client) }
            pipe(client.getInputStream(), r.getOutputStream())
            closeQuietly(r, client)
        } catch (e: IOException) {
            // client went away
        } finally {
            link?.inflight?.decrementAndGet()
            closeQuietly(remote, client)
        }
    }

    private fun reply(out: OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (e: IOException) {
            // connection closed
        }
    }

    private fun closeQuietly(vararg s: Socket?) = s.forEach { runCatching { it?.close() } }
}
