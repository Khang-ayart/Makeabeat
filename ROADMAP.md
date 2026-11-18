# BandStudio — Lộ trình & tiêu chí

## Tầm nhìn
Người dùng đưa **vocal mộc** (rap, melodic hoặc pop). App dựng sẵn một bản nhạc từ các nhạc cụ có sẵn (kể cả Looper),
người dùng nghe và chỉnh theo ý. Câu hỏi app phải trả lời được: *"Tôi không biết gì về âm nhạc, tôi có làm ra một bài nhạc
chỉ với app này không?"*

**Tiêu chí nghiệm thu:** người chưa từng dùng app làm ra một bài hoàn chỉnh trong dưới 10 phút, không có nốt sai tông,
không cần biết nhạc lý. Thử với người thật.

App chạy offline, miễn phí, không tài khoản. "Tự dựng" là bộ lắp ráp dựa trên luật và mẫu có giấy phép (CC0),
không phải mô hình AI sinh âm thanh.

## Nguyên tắc làm việc
- `main` = bản đã kiểm chứng. Mỗi tính năng một nhánh `feature/...`, xong thì cất, **ghép tất cả một lần ở cuối**.
- Kotlin chỉ biên dịch được trên CI: chạy tay workflow trên từng nhánh khi nó xong.

## Hướng dẫn cho bộ dựng beat tự động (ghi lại để khỏi nhầm)
Cách đệm nhạc cụ giai điệu phụ thuộc loại vocal:
- **Rap thuần:** piano/guitar chỉ cần **đệm** (hợp âm, vòng hợp âm theo tông). Không bám giọng.
- **Melodic hoặc pop (có giai điệu):** piano/guitar/synth phải **đánh theo nốt của giọng hát** (ví dụ giọng hát
  "tằng tăng tăng tăng" thì nhạc cụ đánh lên đúng các nốt đó), không chỉ đệm hợp âm. Nghĩa là cần đo cao độ giọng hát,
  cắt thành nốt, rồi cho nhạc cụ chơi giai điệu (đồng nhịp hoặc nhắc lại, có thể thêm quãng tám/quãng ba) cộng với đệm.
- Người dùng chọn kiểu (Rap / Melodic) hoặc app tự nhận: giọng có nốt ổn định thì coi là melodic.

## Các mốc
1. [x] Engine chính xác, đa track, piano roll, step sequencer, quantize, undo/redo, giảm nhiễu mic (đã kiểm thử)
2. [x] Looper: pad nhịp lặp theo thể loại, mẫu CC0, tông chung, Keyboard, reverb/nén bus, swing, Ngẫu nhiên, nghe thử
3. [ ] Looper mở rộng: Synth + Pad, Trap/Melodic/Drill/Afrobeats, hi-hat roll, 808 glide (đã làm, nằm trong nhánh)
4. [ ] Sampler nhạc cụ thật: piano, bass, guitar
5. [ ] Thu giọng theo beat + mixer + hiệu ứng giọng + xuất file
6. [ ] Phân tích giọng: BPM/bar do người dùng nhập, đo cao độ, tông, ngắt câu
7. [ ] Bộ dựng bài tự động (nối 6 vào Looper và các nhạc cụ), có chế độ Rap (đệm) và Melodic (bám nốt)
8. [ ] Thử với người thật, chỉnh lại
