# Build do APK pelo GitHub Actions

O APK do Santtos Player pode ser gerado sem Android Studio local.

1. Envie este projeto para um repositório GitHub.
2. Abra a aba **Actions**.
3. Selecione **Build Santtos Player APK**.
4. Clique em **Run workflow**.
5. Quando o job ficar verde, abra a execução.
6. Em **Artifacts**, baixe **SanttosPlayer-v0.1.2-debug**.
7. Extraia o ZIP do artifact; dentro estará `SanttosPlayer-v0.1.2-debug.apk`.

O workflow usa Java 17, Android SDK 35 e Gradle 8.11.1 para manter o build reproduzível.
