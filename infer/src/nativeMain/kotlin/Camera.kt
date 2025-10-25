import Utils.check
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import platform.native.CreateImage
import platform.native.SupportFormat
import platform.posix.*
import platform.videodev2.*
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
abstract class Camera(val fd: Int) : Video {
    companion object {
        const val BUFFER_COUNT = 4

        fun open(source: String): Camera {
            val fd = open(source, O_RDWR)
            fd.check("打开摄像头")
            sequence {
                yield(CameraS(fd))
                yield(CameraM(fd))
            }.forEach {
                memScoped {
                    val fmt = alloc<v4l2_format>()
                    fmt.type = it.bufType()
                    if (ioctl(fd, VIDIOC_G_FMT, fmt.ptr) == 0) return it
                }
            }
            close(fd)
            throw Error("打开摄像头失败")
        }
    }

    data class Resolution(val w: UInt, val h: UInt, val format: UInt, val desc: String)
    data class Memory(val ptr: COpaquePointer, val size: UInt)

    abstract fun bufType(): v4l2_buf_type
    abstract fun setFormat(r: Resolution, fmt: v4l2_format)
    abstract fun getFormat(fmt: v4l2_format): Resolution
    abstract fun queryBuf(buf: v4l2_buffer): Pair<UInt, UInt>
    abstract fun dequeueBuf(buf: v4l2_buffer)

    override fun close() {
        close(fd)
    }

    @OptIn(ExperimentalTime::class)
    override fun frames() = flow {
        val timeBegin = TimeSource.Monotonic.markNow()
        val resolution = setResolution()
        setFrameRate(resolution)
        val buffers = mapBuffers()
        setStreamOn()
        try {
            while (true) {
                memScoped {
                    val buf = alloc<v4l2_buffer>()
                    buf.type = bufType()
                    buf.memory = V4L2_MEMORY_MMAP
                    withContext(Dispatchers.IO) { dequeueBuf(buf) }
                    val w = resolution.w.toInt()
                    val h = resolution.h.toInt()
                    val image = CreateImage(buffers[buf.index.toInt()].ptr, w, h, resolution.format)
                    ioctl(fd, VIDIOC_QBUF, buf.ptr).check("VIDIOC_QBUF")
                    emit(Video.Frame(timeBegin.elapsedNow(), image!!))
                }
            }
        } finally {
            setStreamOff()
            unmapBuffers(buffers)
        }
    }.buffer(1)

    fun setResolution() = memScoped {
        sequence {
            val fmtDesc = alloc<v4l2_fmtdesc>()
            fmtDesc.type = bufType()
            fmtDesc.index = 0u
            while (ioctl(fd, VIDIOC_ENUM_FMT, fmtDesc.ptr) == 0) {
                val desc = fmtDesc.description.reinterpret<ByteVar>().toKString()
                println("Found format: $desc")
                if (SupportFormat(fmtDesc.pixelformat)) {
                    val fsize = alloc<v4l2_frmsizeenum>()
                    fsize.pixel_format = fmtDesc.pixelformat
                    fsize.index = 0u
                    while (platform.linux.ioctl(fd, VIDIOC_ENUM_FRAMESIZES, fsize.ptr) == 0) {
                        val (w, h) = maxFrameSize(fsize)
                        println("  ${w}x${h}")
                        yield(Resolution(w, h, fmtDesc.pixelformat, desc))
                        ++fsize.index
                    }
                }
                ++fmtDesc.index
            }
        }.maxByOrNull { it.w * it.h }.let {
            val fmt = alloc<v4l2_format>()
            fmt.type = bufType()
            if (it != null) {
                setFormat(it, fmt)
                if (ioctl(fd, VIDIOC_S_FMT, fmt.ptr) == 0) return@let it
            }
            ioctl(fd, VIDIOC_G_FMT, fmt.ptr).check("VIDIOC_S_FMT & VIDIOC_G_FMT")
            getFormat(fmt)
        }.also {
            println("Set to highest resolution: ${it.w}x${it.h} (${it.desc})")
        }
    }

    fun setFrameRate(resolution: Resolution) = memScoped {
        sequence {
            val frmival = alloc<v4l2_frmivalenum>()
            frmival.width = resolution.w
            frmival.height = resolution.h
            frmival.pixel_format = resolution.format
            frmival.index = 0u
            while (ioctl(fd, VIDIOC_ENUM_FRAMEINTERVALS, frmival.ptr) == 0) {
                yield(maxFrameRate(frmival))
                ++frmival.index
            }
        }.maxByOrNull { (num, denom) -> denom.toFloat() / num.toFloat() }.let {
            val parm = alloc<v4l2_streamparm>()
            parm.type = bufType()
            if (it != null) {
                parm.parm.capture.timeperframe.numerator = it.first
                parm.parm.capture.timeperframe.denominator = it.second
                if (ioctl(fd, VIDIOC_S_PARM, parm.ptr) ==0) return it.second.toDouble() / it.first.toDouble()
            }
            if (ioctl(fd, VIDIOC_G_PARM, parm.ptr) < 0) return 1.0
            parm.parm.capture.timeperframe.denominator.toDouble() / parm.parm.capture.timeperframe.numerator.toDouble()
        }.also {
            println("Set fps to $it")
        }
    }

    fun mapBuffers() = memScoped {
        val req = alloc<v4l2_requestbuffers>()
        req.count = BUFFER_COUNT.toUInt()
        req.type = bufType()
        req.memory = V4L2_MEMORY_MMAP
        ioctl(fd, VIDIOC_REQBUFS, req.ptr).check("VIDIOC_REQBUFS")
        Array(BUFFER_COUNT) {
            val buf = alloc<v4l2_buffer>()
            buf.type = bufType()
            buf.memory = V4L2_MEMORY_MMAP
            buf.index = it.toUInt()
            val (offset, size) = queryBuf(buf)
            val ptr = mmap(null, size.toULong(), PROT_READ or PROT_WRITE, MAP_SHARED, fd, offset.toLong())
            Memory(ptr!!, size)
        }
    }

    fun unmapBuffers(buffers: Array<Memory>) = buffers.forEach { munmap(it.ptr, it.size.toULong()) }


    fun setStreamOn() = memScoped {
        val type = alloc<UIntVarOf<v4l2_buf_type>>()
        type.value = bufType()
        ioctl(fd, VIDIOC_STREAMON, type.ptr).check("VIDIOC_STREAMON")
    }

    fun setStreamOff() = memScoped {
        val type = alloc<UIntVarOf<v4l2_buf_type>>()
        type.value = bufType()
        ioctl(fd, VIDIOC_STREAMOFF, type.ptr).check("VIDIOC_STREAMOFF")
    }

    private fun maxFrameSize(size: v4l2_frmsizeenum) = when (size.type) {
        V4L2_FRMSIZE_TYPE_DISCRETE -> Pair(size.discrete.width, size.discrete.height)
        V4L2_FRMSIZE_TYPE_STEPWISE -> Pair(size.stepwise.max_width, size.stepwise.max_height)
        else -> Pair(0u, 0u)
    }

    private fun maxFrameRate(interval: v4l2_frmivalenum) = when (interval.type) {
        V4L2_FRMIVAL_TYPE_DISCRETE -> Pair(interval.discrete.numerator, interval.discrete.denominator)
        V4L2_FRMIVAL_TYPE_STEPWISE -> Pair(interval.stepwise.max.numerator, interval.stepwise.max.denominator)
        else -> Pair(1u, 1u)
    }

    class CameraS(fd: Int) : Camera(fd) {
        override fun bufType() = V4L2_BUF_TYPE_VIDEO_CAPTURE

        override fun setFormat(r: Resolution, fmt: v4l2_format) {
            fmt.type = bufType()
            fmt.fmt.pix.height = r.h
            fmt.fmt.pix.width = r.w
            fmt.fmt.pix.pixelformat = r.format
            if (r.format in setOf(V4L2_PIX_FMT_MJPEG, V4L2_PIX_FMT_JPEG)) fmt.fmt.pix.sizeimage = r.w * r.h * 3u
        }

        override fun getFormat(fmt: v4l2_format) =
            Resolution(fmt.fmt.pix.width, fmt.fmt.pix.height, fmt.fmt.pix.pixelformat, "auto")

        override fun queryBuf(buf: v4l2_buffer) = memScoped {
            ioctl(fd, VIDIOC_QUERYBUF, buf.ptr).check("VIDIOC_QUERYBUF")
            ioctl(fd, VIDIOC_QBUF, buf.ptr).check("VIDIOC_QBUF")
            Pair(buf.m.offset, buf.length)
        }

        override fun dequeueBuf(buf: v4l2_buffer) = memScoped {
            ioctl(fd, VIDIOC_DQBUF, buf.ptr).check("VIDIOC_DQBUF")
        }
    }

    class CameraM(fd: Int) : Camera(fd) {
        override fun bufType() = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE

        override fun setFormat(r: Resolution, fmt: v4l2_format) {
            fmt.type = bufType()
            fmt.fmt.pix_mp.height = r.h
            fmt.fmt.pix_mp.width = r.w
            fmt.fmt.pix_mp.pixelformat = r.format
        }

        override fun getFormat(fmt: v4l2_format) =
            Resolution(fmt.fmt.pix_mp.width, fmt.fmt.pix_mp.height, fmt.fmt.pix_mp.pixelformat, "auto")

        override fun queryBuf(buf: v4l2_buffer) = memScoped {
            val plane = alloc<v4l2_plane>()
            buf.m.planes = plane.ptr
            buf.length = 1u
            ioctl(fd, VIDIOC_QUERYBUF, buf.ptr).check("VIDIOC_QUERYBUF")
            ioctl(fd, VIDIOC_QBUF, buf.ptr).check("VIDIOC_QBUF")
            Pair(plane.m.mem_offset, plane.length)
        }

        override fun dequeueBuf(buf: v4l2_buffer) = memScoped {
            val plane = alloc<v4l2_plane>()
            buf.m.planes = plane.ptr
            buf.length = 1u
            ioctl(fd, VIDIOC_DQBUF, buf.ptr).check("VIDIOC_DQBUF")
        }
    }
}
