package com.dangerfield.cards.libraries.core.logging

/**
 * Extra key marking a log entry as a structured app event. `GrafanaLogTree`
 * (in `:libraries:telemetry:impl`) forwards entries carrying this key to
 * Grafana Cloud; every other tree treats them as ordinary Info logs, so the
 * same call lands in logcat and Sentry breadcrumbs for free.
 */
const val EXTRA_APP_EVENT = "app_event"

/**
 * The single blessed way to emit an app event. Names are dot-namespaced
 * snake_case (`matchmaking.abandoned`, `hand.completed`) — see
 * `docs/plans/client-app-events-otel.md` for the taxonomy. Events fire on
 * user actions and state transitions, never per-frame or per-poll.
 *
 * Null attribute values are dropped; scalar types keep their type so numeric
 * aggregation stays possible downstream, everything else is stringified.
 */
fun Logger.logEvent(name: String, vararg attributes: Pair<String, Any?>): LogId? =
    i { scope ->
        scope.extra(EXTRA_APP_EVENT, name)
        attributes.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is String -> scope.extra(key, value)
                is Boolean -> scope.extra(key, value)
                is Int -> scope.extra(key, value)
                is Long -> scope.extra(key, value)
                is Float -> scope.extra(key, value)
                is Double -> scope.extra(key, value)
                else -> scope.extra(key, value.toString())
            }
        }
        name
    }
