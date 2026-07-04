// Marks a Retrofit endpoint that must NOT be signed — i.e. the public activation call, which runs
// before any credentials exist. The signing interceptor reads this off the request's Invocation
// tag and skips header injection. Everything without this annotation is signed.
package app.balancee.smartpump.display.data.network

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Unsigned
