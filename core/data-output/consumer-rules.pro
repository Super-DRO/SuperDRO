# The R8 rules that each app with core:data-output gets (consumerKeepRules in build.gradle.kts).

# Eclipse Paho MQTT client. Paho makes its logger (JSR47Logger), its message catalog, and
# its network modules (ServiceLoader of NetworkModuleFactory) through reflection. Without
# this rule, the MQTT client fails at each start.
-keep class org.eclipse.paho.client.mqttv3.** { *; }

# BouncyCastle TLS. The data output uses the lightweight API (BcTlsCrypto), not the JCA
# provider. The LDAP classes of the provider refer to javax.naming, which Android does not have.
-dontwarn javax.naming.**
