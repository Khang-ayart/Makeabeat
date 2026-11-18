# Nguồn mẫu nhạc cụ

Mẫu được cắt, chuẩn hoá và chuyển thành mono 16-bit 44,1 kHz từ các bộ sau (script dựng: `tools/build_instruments.py`).

| Nhạc cụ | Bộ mẫu | Giấy phép |
|---|---|---|
| Piano | Splendid Grand Piano (AKAI, chuyển SFZ bởi kinwie / P. Eastman), https://github.com/sfzinstruments/splendidgrandpiano | Public Domain |
| Bass | Karoryfer Swagbass, https://github.com/sfzinstruments/karoryfer.swagbass | CC0 1.0 |
| Electric Guitar | Karoryfer Emilyguitar, https://github.com/sfzinstruments/karoryfer.emilyguitar | CC0 1.0 |
| Acoustic Guitar | Karoryfer Shinyguitar (bản "acoustic" của archtop), https://github.com/sfzinstruments/karoryfer.shinyguitar | CC0 1.0 |

Không có mẫu nào yêu cầu ghi công; danh sách này để minh bạch nguồn gốc.

Ghi chú cao độ: tên nốt trong tệp nguồn của Splendid Grand Piano thấp hơn thật 1 quãng tám và của Swagbass cao hơn thật 1 quãng tám
(đo phổ từng tệp). `tools/build_instruments.py` bù PIANO_OFFSET = +12, BASS_OFFSET = -12 nên `root` trong manifest là cao độ thật.
