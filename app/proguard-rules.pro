# Regras do kotlinx.serialization (só importam se ativar minify no release)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class br.com.strattonpay.app.data.** { *** Companion; }
-keepclasseswithmembers class br.com.strattonpay.app.data.** { kotlinx.serialization.KSerializer serializer(...); }
