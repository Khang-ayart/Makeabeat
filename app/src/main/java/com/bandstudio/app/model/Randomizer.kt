package com.bandstudio.app.model

import kotlin.random.Random

/**
 * Bộ chọn "Ngẫu nhiên" cho lưới Looper: mỗi hàng chọn một trong 4 pad hoặc để trống, sao cho luôn ra một beat có nền
 * (kick, snare, hi-hat và bass) và có thể thêm percussion / keyboard. Mọi pad trong cùng thể loại đều hợp nhau,
 * nên bất kỳ tổ hợp nào cũng nghe được, người dùng không thể chọn "sai".
 */
object Randomizer {
    /** Xác suất hàng có mặt (kick, snare, hat, perc, bass, keys). */
    private val presence = floatArrayOf(1f, 1f, 1f, 0.4f, 1f, 0.75f)

    /** Trả về pad (0..23) cho từng hàng, hoặc -1 nếu hàng đó tắt. Cùng [rng] thì cùng kết quả. */
    fun pick(rng: Random): IntArray = IntArray(SoundCat.entries.size) { row ->
        if (rng.nextFloat() < presence[row]) row * PADS_PER_ROW + rng.nextInt(PADS_PER_ROW) else -1
    }
}
