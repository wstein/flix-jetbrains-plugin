// Tests exercised through the real IDE, language server, and native SM test runner.

mod UiTests {

    import java.lang.{Thread => JThread}

    @Test
    def passes(): Unit \ IO = JThread.sleep(1_000i64)

    @Test
    def waits(): Unit \ IO = JThread.sleep(30_000i64)
}
