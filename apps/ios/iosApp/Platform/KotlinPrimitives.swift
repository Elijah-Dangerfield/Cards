import ComposeApp

/// Helper conversions between Swift primitives and their Compose-generated Kotlin twins; add new bridges here to keep usage consistent across the iOS codebase.
extension Int64 {
    var kotlinLong: KotlinLong { KotlinLong(value: self) }
}

extension Optional where Wrapped == Int64 {
    var kotlinLong: KotlinLong? { map { KotlinLong(value: $0) } }
}

extension KotlinDouble {
    var asDouble: Double { doubleValue }
}

extension Optional where Wrapped == KotlinDouble {
    var asDouble: Double? { self?.doubleValue }
}

extension KotlinInt {
    var asInt: Int { intValue }
}

extension Optional where Wrapped == KotlinInt {
    var asInt: Int? { self?.intValue }
}
