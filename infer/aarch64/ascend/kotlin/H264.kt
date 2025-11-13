typealias DecodeH264 = common.DecodeH264FFmpeg
typealias EncodeH264 = EncodeH264ACL

fun wStride(w: Int) = (w + 15) / 16 * 16
fun hStride(h: Int) = (h + 1) / 2 * 2
