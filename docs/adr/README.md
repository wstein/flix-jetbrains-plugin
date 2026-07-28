# Architecture decision records

Short records of decisions that shape this plugin's architecture, kept so the reasoning
survives the change that motivated it.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-single-language-owner.md) | One language owner and one LSP client | Accepted |
| [0002](0002-native-jvm-debugger.md) | IntelliJ's Java debugger is the sole JDWP owner | Accepted |
| [0003](0003-cross-module-contracts.md) | Cross-module contracts stay pure functions | Accepted |

## Format

Each record states the context that forced a choice, the decision, and the consequences
that follow from it — including the ones that are inconvenient. A decision that turns out
to be wrong is superseded by a new record rather than edited in place, so the history of
the reasoning stays readable.
