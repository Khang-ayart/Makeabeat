# BandStudio

Phòng thu âm nhạc cá nhân trên Android, theo hướng mini DAW kiểu BandLab: **offline, miễn phí, không quảng cáo, không tài khoản**. Manifest không khai báo quyền INTERNET.

## Cách dùng

1. **Danh sách** → *Tạo bản nhạc mới*.
2. **Studio** (màn hình chính): *Thêm nhạc cụ* (Piano, Acoustic Guitar, Electric Guitar, Bass, Drum Kit, Drum Pad, **Looper**). Mỗi nhạc cụ là một track; M = tắt tiếng, S = nghe riêng; chạm tên track để chỉnh âm lượng / pan.
3. Chạm vào **ô trống** trên track để tạo clip (2 ô nhịp). Chạm clip để chọn, chạm lần nữa (hoặc *Sửa*) để mở trình soạn. *Nhân đôi / Lùi / Tiến / ± ô / Xóa* nằm ở thanh dưới.
4. **Trình soạn**: Piano Roll + phím (nhạc cụ giai điệu), hoặc Step Sequencer + Drum Pad (trống). Đoạn đang sửa phát lặp, các track khác vẫn chạy làm nền. Bấm ● để ghi trực tiếp.
5. **Looper**: chọn *Looper*, rồi chọn thể loại (Ballad, Pop, Hip-hop, Trap/808, Lo-fi, R&B, Rock, EDM, Jazz, Acoustic/Folk, Latin). Mỗi thể loại cho lưới pad 5 hàng — Kick, Snare, Hi-hat, Percussion, 808/Bass — mỗi hàng **4 lựa chọn** hợp thể loại đó (ví dụ Ballad: kick mềm/trầm, snare chổi/gõ viền; Trap: kick 808 boom, hi-hat tick ngắn, 808 dài). Không có loop dựng sẵn: bạn tự bấm pad, bấm ● để ghi, hoặc xếp nhịp trên lưới. Đổi thể loại sau này ở hộp thoại track (nốt giữ nguyên, âm đổi theo).
6. Quay lại Studio, đặt BPM, bấm Phát. Chạm số ô nhịp trên thước để đổi vị trí phát; *Lặp* để lặp cả bài.

Dự án tự lưu trên máy (JSON trong bộ nhớ riêng của ứng dụng). Có Undo / Redo.

## Dựng APK bằng GitHub Actions

Đẩy lên `main` (hoặc *Run workflow*), mở lần chạy → **Artifacts** → `BandStudio-apk`. Workflow chạy 3 thứ theo thứ tự: kiểm thử lõi C++ (g++), unit test Kotlin, rồi dựng APK. APK ký bằng khóa debug để cài thử; khi phát hành hãy tạo khóa riêng.

## Kiến trúc

```
app/src/main/cpp/
  engine_core.*     Engine trung tâm: BPM, vị trí phát, lịch sự kiện theo tick, loop, mixer. KHÔNG phụ thuộc Android
  voices.h          Tổng hợp tạm: harmonic, Karplus-Strong, trống, và pad Looper (tone + nhiễu + méo từ bộ thông số Patch). Về sau thay bằng sampler SoundFont
  song.h            Dữ liệu bài nhạc bất biến, UI -> luồng âm thanh qua con trỏ nguyên tử
  noise_reducer.*   Lọc ồn ngoại tuyến (trừ phổ) cho bản thu giọng hát
  audio_engine.*    Nối engine với luồng Oboe (tự mở lại khi rút/cắm tai nghe)
  spsc_queue.h      Hàng đợi lock-free UI -> luồng âm thanh
app/src/main/java/com/bandstudio/app/
  model/            Project / Track / Clip / Note, Quantizer, mã hóa gửi engine (v2), lưu JSON, Sounds.kt (danh mục 39 âm + 11 thể loại)
  StudioViewModel   Trạng thái, Undo/Redo, ghi MIDI có bù độ trễ
  ui/               Home, Studio (timeline), Editor (Piano Roll, Drum Sequencer, Pad, phím)
native-tests/core_test.cpp   30 bài kiểm thử lõi
app/src/test/               Unit test Kotlin (Quantizer, mã hóa bài nhạc)
```

**Nguyên tắc**: chỉ có MỘT đồng hồ, nằm trong engine. Vị trí = `baseBeat + frames × beatsPerFrame` tính từ số nguyên nên không trôi nhịp; track chỉ là dữ liệu mà engine phát theo tick, nên không nhạc cụ nào có bộ đếm riêng. Callback âm thanh không cấp phát, không khóa, không JNI. Hạt giống nhiễu của nốt lấy từ (tick, track, nốt) nên mỗi vòng lặp giống hệt nhau từng bit.

## Đã kiểm chứng (prototype đồng bộ, mục 9 của yêu cầu)

| Yêu cầu | Kết quả test |
|---|---|
| Track trống chạy loop | Hit rơi đúng khung hình, kể cả độ dài vòng lẻ (133 BPM / 44.1 kHz, 20 vòng, lệch < 1 khung hình) |
| Bass chạy đồng bộ với trống | Trống + bass + piano lặp 8 vòng: các vòng giống hệt từng bit |
| Piano ghi và phát MIDI | Nốt ở tick thô (13, 977) phát đúng khung hình; Quantize 1/4, 1/8, 1/16, 1/32 qua unit test |
| Đổi BPM các track vẫn đồng bộ | Sau 2 lần đổi BPM, lệch giữa các track ≤ 3 khung hình (0.06 ms) |
| Bật/tắt track không làm lệch track khác | Tắt rồi bật track trống: bass không đổi (sai lệch 0), nhịp trống sau khi bật lại lệch ≤ 5e-6 |

Ngoài ra: cao độ 4 nhạc cụ giai điệu sai ≤ 2.4 cent; 32 voice cùng lúc chạy nhanh hơn thời gian thực ~100 lần trên máy tính; chạy sạch dưới ASan/UBSan; lọc ồn giảm ~19 dB nhiễu nền, giữ tiếng trong ±1 dB.

## Chưa có

- Âm sắc hiện là tổng hợp tạm (nghe "điện tử"; brush, conga, tambourine kém thật nhất) nên chưa có sampler SoundFont/mẫu thật. Danh mục Looper chỉ là bộ số nên dễ chỉnh tai ở `Sounds.kt`.
- Looper: chưa chọn được tông (bass cố định quanh A), chưa có swing cho Jazz.
- Chưa thu giọng hát, chưa nối bộ lọc ồn vào micro, chưa có hiệu ứng (EQ, reverb), chưa xuất WAV/MP3.
- Guitar chưa có chế độ chọn hợp âm / quạt dây; chưa có thư viện Pattern lưu dùng lại; chưa chỉnh vùng lặp bắt đầu/kết thúc tùy ý; chưa kéo-thả clip và kéo dài nốt.
- Giao diện **chưa được chạy thử trên máy thật** (CI chỉ xác nhận biên dịch và test). Cần xem độ trễ, xrun và cảm giác chạm trên điện thoại.
