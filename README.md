# BandStudio

Phòng thu âm nhạc cá nhân trên Android: **offline, miễn phí, không quảng cáo, không tài khoản**.
Đây là bản **nền tảng âm thanh** (giai đoạn 1): metronome chính xác theo sample, synth nhiều nốt, bàn phím piano cảm ứng.

## Dựng APK bằng GitHub Actions

1. Tạo repo mới trên GitHub, giải nén dự án này và đẩy toàn bộ lên nhánh `main`.
2. Vào tab **Actions** → workflow **Build APK** tự chạy (hoặc bấm **Run workflow**).
3. Khi xong, mở lần chạy đó → mục **Artifacts** → tải `BandStudio-apk` → giải nén ra `app-release.apk`.
4. Chép sang điện thoại và cài (cho phép "cài ứng dụng từ nguồn không xác định").

Workflow chạy 2 job: `native-tests` (kiểm thử lõi âm thanh bằng g++, vài giây) rồi mới `apk`.
APK đang ký bằng khóa debug để cài thử được ngay; khi phát hành thật hãy tạo khóa riêng.

## Cấu trúc

```
app/src/main/cpp/
  engine_core.h/.cpp   Lõi: lập lịch nhịp, metronome, synth. KHÔNG phụ thuộc Android/Oboe
  audio_engine.h/.cpp  Nối lõi với luồng âm thanh Oboe (mở lại stream khi rút tai nghe)
  spsc_queue.h         Hàng đợi lock-free UI -> luồng âm thanh
  jni_bridge.cpp       Cầu JNI
app/src/main/java/com/bandstudio/app/
  audio/AudioEngine.kt Khai báo JNI
  StudioViewModel.kt   Trạng thái UI
  ui/                  Giao diện Jetpack Compose (transport, bàn phím)
native-tests/core_test.cpp   10 bài kiểm thử lõi âm thanh chạy trên máy tính
```

## Nguyên tắc thiết kế (giữ nguyên khi mở rộng)

- **Callback âm thanh là vùng cấm**: không cấp phát, không khóa mutex, không JNI, không log. UI chỉ nói chuyện với engine qua `SpscQueue` và biến nguyên tử.
- **Thời gian tính bằng khung hình (sample), không bằng mili giây.** Vị trí phách = `baseBeat + frames × beatsPerFrame` tính từ số nguyên nên không trôi nhịp; đổi tempo thì "rebase" để liên tục. Bài test xác nhận phách rơi đúng khung hình và kết quả giống hệt từng bit với mọi kích thước buffer.
- **`EngineCore` tách khỏi Oboe** để test trên máy tính và để xuất bản phối sau này chỉ cần gọi `render()` trong vòng lặp offline.
- Manifest **không có quyền INTERNET**, nên ứng dụng không thể gửi dữ liệu đi đâu.

## Lộ trình tiếp theo

1. Sequencer theo tick (`kPpq = 960`): clip chứa nốt, phát đúng sample, vòng lặp.
2. Sampler đọc SoundFont/SF2 thay synth thử (piano, guitar, bass, bộ trống).
3. Piano Roll + Quantize + Scale Lock; Drum Sequencer.
4. Lưu/mở dự án (`project.json` + WAV), Undo/Redo.
5. Mixer, thu âm (input Oboe, bù độ trễ), hiệu ứng, xuất WAV/MP3.

## Giới hạn hiện tại

- Chỉ có một âm sắc synth tạm; chưa có sampler thật, chưa thu âm, chưa lưu dự án.
- Độ trễ phụ thuộc máy; dòng thông tin trên màn hình hiển thị độ trễ ước tính và số xrun để bạn theo dõi.
- Giao diện đang để chữ tiếng Việt trực tiếp trong code; sẽ chuyển sang `strings.xml` khi cần đa ngôn ngữ.
