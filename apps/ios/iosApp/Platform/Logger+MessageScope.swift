import ComposeApp

private final class BlockMessageScope: MessageScope {
    private let builder: (Scope) -> String?

    init(builder: @escaping (Scope) -> String?) {
        self.builder = builder
    }

    func produce(scope: Scope) -> String? {
        builder(scope)
    }
}

private func makeMessageScope(_ builder: @escaping (Scope) -> String?) -> MessageScope {
    BlockMessageScope(builder: builder)
}

extension Logger {
    
    @discardableResult
    func v(scope builder: @escaping (Scope) -> String?) -> Any? {
        return v(messageScope: makeMessageScope(builder))
    }

    @discardableResult
    func v(_ builder: @escaping () -> String?) -> Any? {
        return v(scope: { _ in builder() })
    }

    @discardableResult
    func d(scope builder: @escaping (Scope) -> String?) -> Any? {
        return d(messageScope: makeMessageScope(builder))
    }

    @discardableResult
    func d(_ builder: @escaping () -> String?) -> Any? {
        return d(scope: { _ in builder() })
    }

    @discardableResult
    func i(scope builder: @escaping (Scope) -> String?) -> Any? {
        return i(messageScope: makeMessageScope(builder))
    }

    @discardableResult
    func i(_ builder: @escaping () -> String?) -> Any? {
        return i(scope: { _ in builder() })
    }

    @discardableResult
    func w(scope builder: @escaping (Scope) -> String?) -> Any? {
        return w(messageScope: makeMessageScope(builder))
    }

    @discardableResult
    func w(_ builder: @escaping () -> String?) -> Any? {
        return w(scope: { _ in builder() })
    }

    @discardableResult
    func e(scope builder: @escaping (Scope) -> String?) -> Any? {
        return e(messageScope: makeMessageScope(builder))
    }

    @discardableResult
    func e(_ builder: @escaping () -> String?) -> Any? {
        return e(scope: { _ in builder() })
    }

    @discardableResult
    func wtf(scope builder: @escaping (Scope) -> String?) -> Any? {
        return wtf(messageScope: makeMessageScope(builder))
    }

    @discardableResult
    func wtf(_ builder: @escaping () -> String?) -> Any? {
        return wtf(scope: { _ in builder() })
    }
}
