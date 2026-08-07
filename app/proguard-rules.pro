# AIDL 인터페이스는 이름이 살아있어야 함 (Shizuku 서비스 바인딩용)
-keep interface com.mttd.IUserService { *; }
-keep class com.mttd.IUserService$Stub { *; }
-keep class com.mttd.service.UserService { *; }
-keep class com.mttd.service.UserService$* { *; }

# Shizuku
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# Protobuf-lite — GeneratedMessageLite 는 리플렉션으로 필드를 찾아 스키마를 구성한다.
# (newMessageInfo 의 필드 문자열이 실제 필드명을 가리킴). R8이 필드명을 바꾸면
# "Field seasonId_ for X not found" 런타임 예외로 릴리스 빌드에서만 파싱이 깨진다.
-keep class com.mttd.proto.price.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
