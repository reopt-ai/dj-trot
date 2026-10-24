package ai.reopt.djtrot.dj

import org.junit.Assert.assertEquals
import org.junit.Test

class JosaTest {
    @Test fun `받침 있으면 으로`() = assertEquals("부산역으로", Josa.ro("부산역"))
    @Test fun `ㄹ 받침은 로`() = assertEquals("서울로", Josa.ro("서울"))
    @Test fun `받침 없으면 로`() = assertEquals("회사로", Josa.ro("회사"))
    @Test fun `한글이 아니면 로`() = assertEquals("KTX로", Josa.ro("KTX"))
}
