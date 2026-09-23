<div align="center">

# 🛡️ NTWall — Root Gerektirmeyen Android Güvenlik Duvarı (Firewall) Uygulaması

### Kök Erişimi Olmadan Uygulama Bazlı İnternet Engelleme, Port/IP Filtreleme ve Canlı Ağ Trafiği İzleme

**NTWall**, Android cihazınızda hangi uygulamanın internete erişebileceğine root (kök) erişimi gerektirmeden siz karar vermenizi sağlayan, %100 açık kaynaklı ve reklamsız bir **güvenlik duvarı (firewall)** uygulamasıdır.

[![Lisans](https://img.shields.io/badge/Lisans-GPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84.svg)](#kurulum)
[![F--Droid](https://img.shields.io/badge/F--Droid-Uyumlu-1976D2.svg)](#kurulum)
[![Root](https://img.shields.io/badge/Root-Gerekmez-success.svg)](#nasıl-çalışır)
[![Reklam](https://img.shields.io/badge/Reklam-Yok-critical.svg)](#gizlilik-ve-şeffaflık)

</div>

---

## İçindekiler

1. [NTWall Nedir? — Root'suz Android Firewall](#ntwall-nedir--rootsuz-android-firewall)
2. [Öne Çıkan Özellikler](#öne-çıkan-özellikler)
3. [Nasıl Çalışır? (VPN Tabanlı Uygulama Engelleme Mimarisi)](#nasıl-çalışır-vpn-tabanlı-uygulama-engelleme-mimarisi)
4. [NTWall'ı Diğer Firewall Uygulamalarından Ayıran Nedir?](#ntwallı-diğer-firewall-uygulamalarından-ayıran-nedir)
5. [Kurulum (F-Droid ve Kaynak Koddan)](#kurulum-f-droid-ve-kaynak-koddan)
6. [Kullanım: Uygulama Nasıl Engellenir?](#kullanım-uygulama-nasıl-engellenir)
7. [Gizlilik ve Şeffaflık](#gizlilik-ve-şeffaflık)
8. [Teknik Detaylar](#teknik-detaylar)
9. [Sınırlamalar](#sınırlamalar)
10. [Sıkça Sorulan Sorular (SSS)](#sıkça-sorulan-sorular-sss)
11. [Katkıda Bulunma](#katkıda-bulunma)
12. [Lisans](#lisans)

---

## NTWall Nedir? — Root'suz Android Firewall

Telefonunuzdaki her uygulama, siz fark etmeden arka planda sürekli veri alışverişi yapar. Bazısı bir sunucuya "hâlâ buradayım" der, bazısı konum bilginizi paylaşır, bazısı da hiçbir işlevsel gerekçesi olmadan sessizce internete çıkar. **Root olmadan uygulama internetten nasıl kesilir?** sorusunun cevabı: Android'in kendi VPN altyapısını kullanan, kök erişimi gerektirmeyen bir **güvenlik duvarı** ile.

**NTWall tam olarak bunu yapar.**

Hiçbir veriyi cihazınızın dışına çıkarmadan, hiçbir sunucuya bağlanmadan, cihazınızdaki tüm ağ trafiğini gerçek zamanlı olarak izler; dilediğiniz uygulamayı tek dokunuşla tamamen susturur, dilediğinize ise yalnızca belirlediğiniz **port** veya **IP/CIDR** aralıklarını kapatır. Kalan tüm trafik, aradan hiç geçmemişçesine olması gerektiği hızda akmaya devam eder.

Bu bir reklam engelleyici veya DNS filtresi değildir. **Bu, cihazınızın ağ katmanına tam anlamıyla hâkim olmanızı sağlayan, gerçek ve tam teşekküllü bir Android güvenlik duvarıdır.**

> **Kısaca:** Root gerektirmeyen firewall · Uygulama bazlı internet engelleme · Port ve IP engelleme · Canlı trafik izleme · Açık kaynak · Reklamsız

---

## Öne Çıkan Özellikler

| | |
|---|---|
| 🚫 **Tek Dokunuşla Uygulama Engelleme** | Herhangi bir uygulamayı internetten anında ve tamamen izole edin. Varsayılan davranış her zaman izinlidir — siz engellemedikçe hiçbir uygulama kısıtlanmaz. |
| 🎯 **Hassas Port ve IP Kural Motoru** | Bir uygulamanın tamamını değil, yalnızca belirli bir portunu (örn. 443) veya belirli bir IP/CIDR aralığını (örn. `10.0.0.0/8`) kapatın. Cerrahi hassasiyette ağ kontrolü. |
| 📡 **Canlı Ağ Trafiği Panosu** | Hangi uygulamanın, hangi sunucuyla, hangi portta, ne kadar veri alışverişi yaptığını saniyesi saniyesine izleyin. Hiçbir bağlantı gözünüzden kaçmaz. |
| 📊 **Detaylı Bayt Sayaçları** | İletilen ve engellenen trafiğin ayrı ayrı kaydını tutan sayaçlarla, uygulamaların gerçek ağ davranışını sayısal olarak görün. |
| 🔁 **Yeniden Başlatma Sonrası Otomatik Koruma** | Cihazınızı yeniden başlattığınızda güvenlik duvarının otomatik olarak devreye girmesini sağlayın; koruma hiç kesintiye uğramasın. |
| ⚡ **Root Erişimi Gerektirmez** | Karmaşık kurulumlara, garanti kaybına veya güvenlik risklerine gerek yok. Android'in resmi `VpnService` API'si üzerinde, tamamen kurallara uygun şekilde çalışır. |
| 🇹🇷 **Yerli ve Milli Türkçe Arayüz** | Uçtan uca Türkçe, sade ve anlaşılır bir kullanıcı deneyimi. |
| 🔓 **%100 Özgür ve Açık Kaynak Yazılım** | Tüm kaynak kodu GPL-3.0 lisansıyla herkese açıktır. Ne yaptığını merak ediyorsanız, satır satır okuyabilirsiniz. |

---

## Nasıl Çalışır? (VPN Tabanlı Uygulama Engelleme Mimarisi)

NTWall, arka planda tek bir `VpnService` çalıştırır ve cihazınızdaki **tüm** IP trafiğini bu servisten geçirir — ama bunu yaparken hiçbir paketi cihazınızın dışına, herhangi bir üçüncü taraf sunucuya göndermez. Her şey, telefonunuzun kendi işlemcisinde, gözünüzün önünde gerçekleşir. VPN burada bir gizlilik aracı değil, Android'in üçüncü taraf uygulamalara ağ trafiğini filtreleme izni verdiği **tek resmi ve root gerektirmeyen mekanizmadır**.

```
┌─────────────┐     ┌──────────────────────────────────────┐     ┌──────────────┐
│  Uygulama   │ ──▶ │           NTWall (Yerel VPN)           │ ──▶ │   İnternet   │
│ (WhatsApp,  │     │                                        │     │              │
│  Chrome...) │     │  1. Paket geldi, hangi uygulamaya ait?  │     │              │
└─────────────┘     │  2. Kurallarım bu uygulamaya ne diyor?  │     └──────────────┘
                     │  3a. ENGELLİ → paket sessizce düşürülür │
                     │  3b. İZİNLİ  → gerçek ağa iletilir      │
                     └──────────────────────────────────────┘
```

Her yeni bağlantı için karar mekanizması şu şekilde işler:

| Uygulamanın Durumu | Sonuç |
|---|---|
| Herhangi bir kural yok | Trafik normal şekilde akar, hiçbir gecikme veya kısıtlama yaşanmaz |
| **Tamamen engelli** | Uygulamaya ait her paket, ağa çıkmadan tünel içinde imha edilir |
| **Port/IP kuralı var** | Yalnızca kural kapsamındaki trafik durdurulur, geri kalanı serbest bırakılır |

Bağlantının hangi uygulamaya ait olduğunu bulmak için NTWall, Android 10 ve üzerinde sistemin resmi `ConnectionOwnerUid` mekanizmasını, daha eski sürümlerde ise çekirdek soket tablolarını kullanır. Sahibi belirlenemeyen hiçbir bağlantıya izin verilmez — şüphe durumunda güvenlik duvarı **her zaman kapalı tarafta hata yapar (fail-closed).**

---

## NTWall'ı Diğer Firewall Uygulamalarından Ayıran Nedir?

Android ekosisteminde bilinen firewall çözümleriyle kıyaslandığında NTWall'ın konumu:

| Özellik | NTWall | Root Gerektiren Firewall'lar (örn. AFWall+) | Diğer Root'suz VPN Firewall'lar |
|---|---|---|---|
| Root erişimi gerekliliği | ❌ Gerekmez | ✅ Gerekir | ❌ Gerekmez |
| Port / IP bazlı kural | ✅ Var | Değişken | Genellikle sınırlı |
| Canlı trafik izleme | ✅ Var | Genellikle yok | Değişken |
| Türkçe arayüz | ✅ Uçtan uca | Değişken | Genellikle yok |
| Açık kaynak (GPL-3.0) | ✅ Evet | Değişken | Değişken |
| Reklam / izleyici | ❌ Yok | Değişken | Değişken |

> Not: Yukarıdaki karşılaştırma genel kategori bazlıdır; belirli bir uygulamayla birebir kıyaslama için ilgili projenin kendi dokümantasyonuna bakınız.

---

## Kurulum (F-Droid ve Kaynak Koddan)

### F-Droid Üzerinden (Önerilen)

NTWall, özgür yazılım prensiplerine tam uyumlu şekilde geliştirildi ve F-Droid deposunda yayınlanmak üzere hazırlandı. Google Play Hizmetlerine, kapalı kaynaklı hiçbir bileşene ve izleyici (tracker) kütüphanesine bağımlılığı yoktur.

### Kaynak Koddan Derleme

Kodun her satırına güvenmek istiyorsanız, uygulamayı kendiniz derleyebilirsiniz:

```bash
git clone https://github.com/<kullanıcı-adınız>/NTWall.git
cd NTWall
./gradlew assembleRelease
```

Gereksinimler: JDK 17 ve Android SDK (API 34). Üretilen APK, `app/build/outputs/apk/release/` klasöründe yer alır.

---

## Kullanım: Uygulama Nasıl Engellenir?

1. **Uygulamayı açın** ve güvenlik duvarını etkinleştirin. Android, VPN bağlantısı için tek seferlik bir onay isteyecektir.
2. **Uygulamalar** sekmesinde, cihazınızda yüklü tüm uygulamaları görürsünüz. Herhangi birine dokunarak internetten anında engelleyebilirsiniz.
3. **Port veya IP kuralı ekleyin**: Bir uygulamayı tamamen değil de yalnızca belirli bir port veya IP aralığında kısıtlamak istiyorsanız, ilgili uygulamanın detay ekranından özel kural tanımlayın.
4. **Trafik** sekmesinden, hangi uygulamanın ne zaman, nereyle, ne kadar veri alışverişi yaptığını canlı olarak izleyin.
5. **Dashboard** ekranında toplam iletilen ve engellenen veri miktarını tek bakışta görün.

---

## Gizlilik ve Şeffaflık

Bir güvenlik duvarı uygulamasının, kendisi bir gizlilik tehdidi olmaması esastır. Bu yüzden NTWall:

- ❌ Hiçbir reklam göstermez.
- ❌ Hiçbir analiz veya kullanım verisi toplamaz.
- ❌ Hiçbir hesap, e-posta veya kişisel bilgi istemez.
- ❌ Hiçbir sunucuya bağlanmaz; NTWall'ın hiçbir sunucu bileşeni yoktur.
- ✅ Tüm kurallarınız ve istatistikleriniz yalnızca cihazınızda saklanır.
- ✅ Tüm kaynak kod açıktır ve bağımsız olarak denetlenebilir.

---

## Teknik Detaylar

- **Mimari:** Jetpack Compose, Room (kalıcı depolama), Hilt (bağımlılık enjeksiyonu), Kotlin Coroutines, DataStore.
- **Ağ katmanı:** Android `VpnService` üzerinde çalışan, kullanıcı alanında (user-space) yazılmış özel bir TCP/UDP ileticisi (`PacketForwarder`) sayesinde izinli trafik gerçek ağa aktarılır.
- **Minimum Android sürümü:** Android 8.0 (API 26).
- **Test kapsamı:** Trafik ileticisi, gerçek soketler üzerinden JVM ortamında çalışan kapsamlı bir birim test paketiyle doğrulanmıştır (el sıkışma, büyük veri transferleri, eşzamanlı bağlantılar, parçalı paketler ve daha fazlası).

---

## Sınırlamalar

Şeffaflık, güvenden önce gelir — bu yüzden ne yapamadığımızı da açıkça söylüyoruz:

- Android işletim sistemi aynı anda yalnızca **tek bir aktif VPN**'e izin verir; bu nedenle NTWall başka bir VPN uygulamasıyla birlikte çalıştırılamaz.
- İzinli trafik yalnızca **IPv4 TCP/UDP** üzerinden iletilir; IPv6 ve ICMP paketleri düşürülür, uygulamalar bu durumda otomatik olarak IPv4'e döner.
- Trafik iletimi kullanıcı alanında gerçekleştiği için, çok yüksek hacimli veri transferlerinde (örn. 4K video akışı) işlemci kullanımı, filtresiz bir bağlantıya kıyasla hafifçe artabilir.

---

## Sıkça Sorulan Sorular (SSS)

**Root olmadan bir uygulamanın interneti nasıl kesilir?**
NTWall'ı kurup güvenlik duvarını etkinleştirdikten sonra, Uygulamalar sekmesinden ilgili uygulamaya dokunmanız yeterlidir. Herhangi bir root işlemi veya teknik bilgi gerekmez.

**VPN kullanmadan uygulama engellemek mümkün mü?**
Android'de root olmadan üçüncü taraf bir uygulamanın ağ trafiğini kontrol edebilmesinin tek resmi yolu `VpnService` API'sidir. NTWall bu API'yi yalnızca yerel filtreleme için kullanır; verileriniz hiçbir sunucuya gönderilmez.

**NTWall verilerimi bir sunucuya mı gönderiyor?**
Hayır. NTWall'ın hiçbir sunucu bileşeni yoktur. "VPN" burada yalnızca Android'in trafiği filtrelemenize izin veren teknik mekanizmasıdır; hiçbir paket cihazınızın dışına, bir üçüncü tarafa gönderilmez.

**Root gerekiyor mu?**
Hayır, hiçbir şekilde gerekmez.

**Uygulama pilimi mi tüketir?**
NTWall, yalnızca kısıtladığınız veya kural tanımladığınız uygulamaların trafiğini işler; kuralsız uygulamalarınız normal hızında ve verimliliğinde çalışmaya devam eder.

**Belirli bir portu veya IP adresini nasıl engellerim?**
İlgili uygulamanın detay ekranından "Kural Ekle" seçeneğini kullanarak port numarası veya IP/CIDR aralığı tanımlayabilirsiniz.

---

## Katkıda Bulunma

Hata bildirimleri, özellik önerileri ve pull request'ler her zaman memnuniyetle karşılanır. Katkıda bulunmadan önce lütfen mevcut açık konuları (issue) inceleyin.

---

## Lisans

Bu proje **GNU General Public License v3.0** ile lisanslanmıştır. Ayrıntılar için [LICENSE](LICENSE) dosyasına bakın.

<div align="center">

**NTWall** — Root Gerektirmeyen Android Güvenlik Duvarı · Ağınız, kurallarınız.

</div>
