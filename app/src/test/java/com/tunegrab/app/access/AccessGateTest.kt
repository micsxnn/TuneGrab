package com.tunegrab.app.access

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A trava de acesso tem DUAS implementações que precisam concordar bit a bit:
 * o Kotlin (aqui, no app) e o Python (tools/passgen.py, que emite as senhas).
 * Se divergirem, ninguém consegue destravar. Os valores "de referência"
 * abaixo foram gerados pelo próprio passgen.py e por hashlib/hmac do Python.
 */
class AccessGateTest {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ---------- PBKDF2 manual (existe porque a API 24 não tem o provider) ----------

    @Test
    fun pbkdf2_bate_com_os_vetores_conhecidos() {
        val pw = "password".toByteArray()
        val salt = "salt".toByteArray()
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            AccessGate.pbkdf2Sha256(pw, salt, 1, 32).hex(),
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            AccessGate.pbkdf2Sha256(pw, salt, 2, 32).hex(),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            AccessGate.pbkdf2Sha256(pw, salt, 4096, 32).hex(),
        )
    }

    @Test
    fun pbkdf2_bate_com_o_formato_real_das_entradas() {
        // Senha canônica de 12 chars + salt de 16 bytes, como no passwords.json.
        val salt = "000102030405060708090a0b0c0d0e0f".unhex()
        assertEquals(
            "a81edfa25ad03bd2095908ee5f5d20cf34eceab754a2f898a495c8feb0771237",
            AccessGate.pbkdf2Sha256("TUNE7K3M9QPD".toByteArray(), salt, 1000, 32).hex(),
        )
    }

    @Test
    fun pbkdf2_bate_com_o_provider_do_jdk() {
        val pw = "TUNEABCD2345"
        val salt = ByteArray(16) { (it * 7).toByte() }
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, 5000, 32 * 8)).encoded
        assertArrayEquals(expected, AccessGate.pbkdf2Sha256(pw.toByteArray(), salt, 5000, 32))
    }

    @Test
    fun pbkdf2_com_dkLen_maior_que_um_bloco() {
        val pw = "password".toByteArray()
        val salt = "salt".toByteArray()
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("password".toCharArray(), salt, 3, 48 * 8)).encoded
        assertArrayEquals(expected, AccessGate.pbkdf2Sha256(pw, salt, 3, 48))
    }

    // ---------- HMAC (usado no binding senha <-> aparelho) ----------

    @Test
    fun hmac_bate_com_o_caso_2_da_rfc_4231() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            AccessGate.hmacSha256("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray()).hex(),
        )
    }

    // ---------- canonicalização da senha digitada ----------

    @Test
    fun senha_digitada_de_qualquer_jeito_vira_o_mesmo_canonico() {
        val esperado = "TUNE7K3M9QPD"
        assertEquals(esperado, AccessGate.canonicalize("TUNE-7K3M-9QPD"))
        assertEquals(esperado, AccessGate.canonicalize("tune-7k3m-9qpd"))
        assertEquals(esperado, AccessGate.canonicalize("  TUNE 7K3M 9QPD \n"))
        assertEquals(esperado, AccessGate.canonicalize("tune7k3m9qpd"))
    }

    @Test
    fun canonico_de_entrada_sem_letras_ou_numeros_fica_vazio() {
        assertEquals("", AccessGate.canonicalize(""))
        assertEquals("", AccessGate.canonicalize("  --- ... "))
    }

    // ---------- código do aparelho (espelho do device_code_from do passgen.py) ----------

    @Test
    fun codigo_do_aparelho_bate_com_o_passgen() {
        assertEquals("AUAN-DA2A", AccessGate.deviceCodeFrom("abc123"))
        assertEquals("K6PP-G2CD", AccessGate.deviceCodeFrom("0123456789abcdef"))
        assertEquals("64YV-N6UZ", AccessGate.deviceCodeFrom("a1b2c3d4e5f60718"))
    }

    @Test
    fun codigo_do_aparelho_ignora_caixa_e_espacos_nas_pontas() {
        assertEquals(AccessGate.deviceCodeFrom("abc123"), AccessGate.deviceCodeFrom("  ABC123  "))
    }

    @Test
    fun codigo_do_aparelho_tem_o_formato_e_o_alfabeto_certos() {
        val alfabeto = AccessGate.ALPHABET
        repeat(50) { i ->
            val code = AccessGate.deviceCodeFrom("android-id-$i")
            assertTrue(code, Regex("[$alfabeto]{4}-[$alfabeto]{4}").matches(code))
        }
    }

    @Test
    fun aparelhos_diferentes_geram_codigos_diferentes() {
        assertNotEquals(AccessGate.deviceCodeFrom("aparelho-1"), AccessGate.deviceCodeFrom("aparelho-2"))
    }

    @Test
    fun canonico_do_codigo_tira_o_traco_e_normaliza_a_caixa() {
        assertEquals("AB2C9XYZ", AccessGate.canonicalDeviceCode("ab2c-9xyz"))
        assertEquals("AB2C9XYZ", AccessGate.canonicalDeviceCode(" AB2C 9XYZ "))
    }
}
