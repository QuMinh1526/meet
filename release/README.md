# SensiTune — bản phát hành

| | |
|---|---|
| File | `SensiTune-1.2.0.apk` |
| Package | `com.example.sensitune` |
| versionCode / versionName | `6` / `1.2.0` |
| minSdk / targetSdk / compileSdk | 26 / 34 / 34 |
| ABI | `arm64-v8a`, `armeabi-v7a` |
| Kích thước | ~1.7 MB |
| Chữ ký | APK Signature Scheme **v2 + v3** (không dùng v1) |
| zipalign | đã align 4 byte (`zipalign -c -v 4` → Verification succesful) |
| Chứng chỉ | `CN=SensiTune, OU=Release, O=SensiTune, L=Hanoi, C=VN`, RSA 4096 |
| SHA-256 chứng chỉ | `47:59:4F:34:3E:3F:80:A8:B2:D6:6C:B3:C2:4C:0C:D6:17:E5:11:0D:5A:F3:88:E0:E2:F6:FF:80:CD:1D:F9:53` |
| SHA-256 APK | xem `SHA256SUMS.txt` |

## Kiểm tra chữ ký

```bash
apksigner verify --verbose --print-certs release/SensiTune-1.2.0.apk
# Verified using v2 scheme (APK Signature Scheme v2): true
# Verified using v3 scheme (APK Signature Scheme v3): true
sha256sum -c release/SHA256SUMS.txt
```

## Cài đặt

```bash
adb install -r release/SensiTune-1.2.0.apk
```

## Thay đổi ở 1.2.0

- **Hai mode:** `Mode ▾` (Sensitivity / Drag) ở đầu menu nổi và màn hình chính; lưu riêng
  theo từng app khi bật “chỉ trong app đã chọn”.
- **Drag mode:** chỉ ngón chạm trong vòng tròn mới bị biến đổi, gain tăng theo quãng kéo
  hoặc tốc độ; 8 đường cong + Perlin (bảng cố định, không rand/seed) + Velocity; Dạng
  In/Out/InOut; lọc hướng; tuỳ chọn dừng khi ra khỏi vòng. Tổng gain luôn ≤ 4.0x.
- **STYLE chung:** Raw, Smooth, Adaptive (1€), Snappy, Elastic (lò xo bậc 2, dt theo
  timestamp event, input 0 → output 0), Precision, S-curve, Power, Stepped; cường độ
  0–100, stiffness/damping cho Elastic, đồ thị xem trước.
- **Overlay DragZoneView:** vòng tròn khoá xuyên chạm, mở khoá để kéo thả, tự khoá sau 15 s,
  vị trí lưu riêng dọc/ngang; khi chưa có zone hợp lệ engine pass-through hoàn toàn.
- **FloatMenu 3 tab** `[Mode hiện tại] [Style] [HUD]`; tab HUD khôi phục đủ chỉ số, thứ tự,
  cỡ chữ, độ mờ, cột/hàng, ngưỡng vàng/đỏ, khoá vị trí và mũi tên 8 px. Nút NHẢ luôn hiện.
- **Cấu hình engine kiểu RCU:** snapshot bất biến hoán đổi atomic, chốt theo từng ngón;
  mọi thay đổi gửi qua AIDL, coalesce/debounce 50 ms ngoài main thread.
- **TestPadActivity** vẽ đường ngón (xanh) và đầu ra (đỏ) bằng đúng DSP native; màn hình
  Debug đọc `getStatus` để hiển thị mode/style/drag/zone mà engine đang áp dụng.
- **Trần hệ số hạ về 4.00** ở màn hình chính và AppPicker theo quy tắc tổng gain ≤ 4.0x.

APK này ký bằng chứng chỉ tự tạo, **khác** với bản debug (`./gradlew assembleDebug`).
Nếu máy đang cài bản debug cùng package, phải gỡ trước:

```bash
adb uninstall com.example.sensitune
adb install release/SensiTune-1.2.0.apk
```

## Ghi chú bảo mật

Keystore dùng để ký bản này **không được commit** vào repo (`.signing/` đã nằm trong
`.gitignore`). Muốn phát hành bản cập nhật cài đè được lên bản này, phải dùng đúng keystore
đó; nếu mất keystore, người dùng sẽ phải gỡ app rồi cài lại.
