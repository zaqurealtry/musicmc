# Music MC 1.3.1 – Minecraft 26.3

## Derleme (Windows)
1. Eclipse Temurin JDK 25 (64-bit) kurun.
2. PowerShell'i bu proje klasöründe açın.
3. `java -version` çıktısının 25.x olduğunu doğrulayın.
4. `./gradlew.bat build --no-daemon` komutunu çalıştırın.
5. Başarılı derlemede `build\libs\music-mc-1.3.1.jar` oluşur. `*-sources.jar` dosyasını mods klasörüne koymayın.

## GitHub Actions ile derleme
Projeyi kendi GitHub hesabınızda yeni, boş bir depoya yükleyin. `.github/workflows/build.yml` her push'ta JDK 25 ile derler ve Actions > ilgili workflow > Artifacts bölümüne JAR'ı koyar. Manuel çalıştırma için Actions > Build > Run workflow kullanılabilir.

## Önemli doğrulama notu
Bu kaynak paketi bu çalışma ortamında derlenemedi: Gradle Wrapper `gradle-9.8.0-bin.zip` dosyasını `services.gradle.org` üzerinden indirmeye çalıştı ancak DNS/ağ erişimi yoktu (`UnknownHostException`). Bu nedenle JAR'ın derlendiği veya oyun içinde test edildiği iddia edilmemektedir. Derleme sonucu ve Minecraft 26.3 oyun testi ayrıca doğrulanmalıdır.
