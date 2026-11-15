# SensiTune — bản phát hành

| | |
|---|---|
| File | `SensiTune-1.0.3.apk` |
| Package | `com.example.sensitune` |
| versionCode / versionName | `4` / `1.0.3` |
| minSdk / targetSdk / compileSdk | 26 / 34 / 34 |
| ABI | `arm64-v8a`, `armeabi-v7a` |
| Kích thước | ~1.6 MB |
| Chữ ký | APK Signature Scheme **v2 + v3** (không dùng v1) |
| zipalign | đã align 4 byte (`zipalign -c -v 4` → Verification succesful) |
| Chứng chỉ | `CN=SensiTune, OU=Release, O=SensiTune, L=Hanoi, C=VN`, RSA 4096 |
| SHA-256 chứng chỉ | `47:59:4F:34:3E:3F:80:A8:B2:D6:6C:B3:C2:4C:0C:D6:17:E5:11:0D:5A:F3:88:E0:E2:F6:FF:80:CD:1D:F9:53` |
| SHA-256 APK | xem `SHA256SUMS.txt` |

## Kiểm tra chữ ký

```bash
apksigner verify --verbose --print-certs release/SensiTune-1.0.3.apk
# Verified using v2 scheme (APK Signature Scheme v2): true
# Verified using v3 scheme (APK Signature Scheme v3): true
sha256sum -c release/SHA256SUMS.txt
```

## Cài đặt

```bash
adb install -r release/SensiTune-1.0.3.apk
```

APK này ký bằng chứng chỉ tự tạo, **khác** với bản debug (`./gradlew assembleDebug`).
Nếu máy đang cài bản debug cùng package, phải gỡ trước:

```bash
adb uninstall com.example.sensitune
adb install release/SensiTune-1.0.3.apk
```

## Ghi chú bảo mật

Keystore dùng để ký bản này **không được commit** vào repo (`.signing/` đã nằm trong
`.gitignore`). Muốn phát hành bản cập nhật cài đè được lên bản này, phải dùng đúng keystore
đó; nếu mất keystore, người dùng sẽ phải gỡ app rồi cài lại.
