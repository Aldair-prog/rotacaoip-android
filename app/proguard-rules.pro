# Ponte JavaScript do painel (os nomes dos métodos precisam continuar iguais)
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# kotlinx.serialization (modelos da API usados pelas notificações)
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class br.com.strattonpay.app.data.** { *** Companion; }
-keepclasseswithmembers class br.com.strattonpay.app.data.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class br.com.strattonpay.app.data.**$$serializer { *; }

# Avisos de bibliotecas opcionais (Tink / OkHttp)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
