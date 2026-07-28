/// Fixture for the UI smoke test. Deliberately minimal.
///
/// The test asserts against line numbers here, so adding or removing a line above `main` breaks it.
/// That fragility is accepted rather than worked around: a smoke test that searched for its own
/// target by content would be one more thing that can silently match the wrong line.
def main(): Unit \ IO =
    let subject = "Flix";
    println("Hello ${subject}!")
