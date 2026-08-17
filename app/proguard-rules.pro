# AIDL 인터페이스는 이름이 살아있어야 함 (shell UID 데몬과의 Binder 핸드셰이크용)
-keep interface com.mttd.IUserService { *; }
-keep class com.mttd.IUserService$Stub { *; }
-keep class com.mttd.service.UserService { *; }
-keep class com.mttd.service.UserService$* { *; }

# shell UID 데몬 진입점. app_process 가 `--class=com.mttd.data.adb.starter.DirectDaemonStarter`
# 문자열로 로드해서 main() 을 부르므로, R8 이 이름을 바꾸거나 지우면 **릴리스 빌드에서만**
# 데몬이 안 뜬다 (앱은 adb-shell 폴백으로 조용히 계속 동작해서 눈치채기 어렵다).
-keep class com.mttd.data.adb.starter.DirectDaemonStarter { *; }

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
