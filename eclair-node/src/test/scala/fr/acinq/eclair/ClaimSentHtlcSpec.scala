package fr.acinq.eclair

import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.Scripts._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ClaimSentHtlcSpec extends FunSuite {

  object Alice {
    val (_, commitKey) = Base58Check.decode("cVuzKWCszfvjkoJyUasvsrRdECriz8hSd1BDinRNzytwnXmX7m1g")
    val (_, finalKey) = Base58Check.decode("cRUfvpbRtMSqCFD1ADdvgPn5HfRLYuHCFYAr2noWnaRDNger2AoA")
    val commitPubKey = Crypto.publicKeyFromPrivateKey(commitKey)
    val finalPubKey = Crypto.publicKeyFromPrivateKey(finalKey)
    val R: BinaryData = Crypto.sha256("this is Alice's R".getBytes("UTF-8"))
    val Rhash: BinaryData = Crypto.sha256(R)
    val H = Crypto.hash160(Rhash)
    val revokeCommit: BinaryData = Crypto.sha256("Alice revocation R".getBytes("UTF-8"))
    val revokeCommitRHash: BinaryData = Crypto.sha256(revokeCommit)
    val revokeCommitH: BinaryData = Crypto.sha256(revokeCommit)
  }

  object Bob {
    val (_, commitKey) = Base58Check.decode("cSupnaiBh6jgTcQf9QANCB5fZtXojxkJQczq5kwfSBeULjNd5Ypo")
    val (_, finalKey) = Base58Check.decode("cQLk5fMydgVwJjygt9ta8GcUU4GXLumNiXJCQviibs2LE5vyMXey")
    val commitPubKey = Crypto.publicKeyFromPrivateKey(commitKey)
    val finalPubKey = Crypto.publicKeyFromPrivateKey(finalKey)
    val R: BinaryData = Crypto.sha256("this is Bob's R".getBytes("UTF-8"))
    val Rhash: BinaryData = Crypto.sha256(R)
    val H = Crypto.hash160(Rhash)
    val revokeCommit: BinaryData = Crypto.sha256("Bob revocation R".getBytes("UTF-8"))
    val revokeCommitRHash: BinaryData = Crypto.sha256(revokeCommit)
    val revokeCommitH: BinaryData = Crypto.sha256(revokeCommit)
  }

  val abstimeout = 3000
  val reltimeout = 2000
  val htlcScript = scriptPubKeyHtlcSend(Alice.finalPubKey, Bob.finalPubKey, abstimeout, reltimeout, Alice.revokeCommitRHash, Alice.Rhash)
  val redeemScript: BinaryData = Script.write(htlcScript)

  // this tx sends money to our HTLC
  val tx = Transaction(
    version = 2,
    txIn = TxIn(OutPoint(Hash.Zeroes, 0), Array.emptyByteArray, 0xffffffffL) :: Nil,
    txOut = TxOut(10 satoshi, pay2wsh(htlcScript)) :: Nil,
    lockTime = 0)

  // this tx tries to spend the previous tx
  val tx1 = Transaction(
    version = 2,
    txIn = TxIn(OutPoint(tx, 0), Array.emptyByteArray, 0xffffffff) :: Nil,
    txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
    lockTime = 0)

  test("Alice can spend this HTLC after a delay") {
    val tx2 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, 0), Array.emptyByteArray, sequence = reltimeout + 1) :: Nil,
      txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = abstimeout + 1)

    val sig = Transaction.signInput(tx2, 0, redeemScript, SIGHASH_ALL, tx.txOut(0).amount, 1, Alice.finalKey)
    val witness = ScriptWitness(sig :: Hash.Zeroes :: redeemScript :: Nil)
    val tx3 = tx2.updateWitness(0, witness)

    Transaction.correctlySpends(tx3, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("Alice cannot spend this HTLC before its absolute timeout") {
    val tx2 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, 0), Array.emptyByteArray, sequence = reltimeout + 1) :: Nil,
      txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = abstimeout - 1)

    val sig = Transaction.signInput(tx2, 0, redeemScript, SIGHASH_ALL, tx.txOut(0).amount, 1, Alice.finalKey)
    val witness = ScriptWitness(sig :: Hash.Zeroes :: redeemScript :: Nil)
    val tx3 = tx2.updateWitness(0, witness)

    val e = intercept[RuntimeException] {
      Transaction.correctlySpends(tx3, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    assert(e.getMessage === "unsatisfied CLTV lock time")
  }

  test("Alice cannot spend this HTLC before its relative timeout") {
    val tx2 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, 0), Array.emptyByteArray, sequence = reltimeout - 1) :: Nil,
      txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = abstimeout + 1)

    val sig = Transaction.signInput(tx2, 0, redeemScript, SIGHASH_ALL, tx.txOut(0).amount, 1, Alice.finalKey)
    val witness = ScriptWitness(sig :: Hash.Zeroes :: redeemScript :: Nil)
    val tx3 = tx2.updateWitness(0, witness)

    val e = intercept[RuntimeException] {
      Transaction.correctlySpends(tx3, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    assert(e.getMessage === "unsatisfied CSV lock time")
  }

  test("Blob can spend this HTLC if he knows the payment hash") {
    val sig = Transaction.signInput(tx1, 0, redeemScript, SIGHASH_ALL, tx.txOut(0).amount, 1, Bob.finalKey)
    val witness = ScriptWitness(sig :: Alice.R :: redeemScript :: Nil)
    val tx2 = tx1.updateWitness(0, witness)
    Transaction.correctlySpends(tx2, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("Blob can spend this HTLC if he knows the revocation hash") {
    val sig = Transaction.signInput(tx1, 0, redeemScript, SIGHASH_ALL, tx.txOut(0).amount, 1, Bob.finalKey)
    val witness = ScriptWitness(sig :: Alice.revokeCommit :: redeemScript :: Nil)
    val tx2 = tx1.updateWitness(0, witness)
    Transaction.correctlySpends(tx2, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }
}
