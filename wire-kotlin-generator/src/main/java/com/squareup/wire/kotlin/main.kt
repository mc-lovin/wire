import com.squareup.wire.EnumAdapter
import com.squareup.wire.FieldEncoding
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoReader
import com.squareup.wire.ProtoWriter
import com.squareup.wire.WireEnum
import com.squareup.wire.internal.Internal
import com.squareup.wire.kotlin.UnkownFieldsBuilder
import com.squareup.wire.kotlin.decodeMessage
import kotlin.Int
import kotlin.String
import kotlin.collections.List
import okio.ByteString

data class Person(
    val name: String,
    val id: Int,
    val email: String? = null,
    val phone: List<PhoneNumber>,
    val unknownFields: ByteString = okio.ByteString.EMPTY
) {
  object ADAPTER : ProtoAdapter<Person>(FieldEncoding.LENGTH_DELIMITED, Person::class.java) {
    override fun encodedSize(value: Person): Int = ProtoAdapter.STRING.encodedSizeWithTag(1, value.name) +
        ProtoAdapter.INT32.encodedSizeWithTag(2, value.id) +
        ProtoAdapter.STRING.encodedSizeWithTag(3, value.email) +
        PhoneNumber.ADAPTER.asRepeated().encodedSizeWithTag(4, value.phone) +
        value.unknownFields.size()

    override fun encode(writer: ProtoWriter, value: Person) {
      ProtoAdapter.STRING.encodeWithTag(writer, 1, value.name)
      ProtoAdapter.INT32.encodeWithTag(writer, 2, value.id)
      ProtoAdapter.STRING.encodeWithTag(writer, 3, value.email)
      PhoneNumber.ADAPTER.asRepeated().encodeWithTag(writer, 4, value.phone)
      writer.writeBytes(value.unknownFields)
    }

    override fun decode(reader: ProtoReader): Person {
      var name: kotlin.String? = null
      var id: kotlin.Int? = null
      var email: kotlin.String? = null
      var phone = mutableListOf<Person.PhoneNumber>()
      val unknownFields = reader.decodeMessage {
        tag -> when(tag) {
        1 -> name = ProtoAdapter.STRING.decode(reader)
        2 -> id = ProtoAdapter.INT32.decode(reader)
        3 -> email = ProtoAdapter.STRING.decode(reader)
        4 -> phone.add(PhoneNumber.ADAPTER.decode(reader))
        else -> UnkownFieldsBuilder.UNKNOWN_FIELD
      }
      }
      return Person(
          name = name ?: throw Internal.missingRequiredFields(name, "name"),
          id = id ?: throw Internal.missingRequiredFields(id, "id"),
          email = email,
          phone = phone,
          unknownFields = unknownFields)
    }
  }

  enum class PhoneType(private val value: Int) : WireEnum {
    vale(0),

    value_(1),

    WORK(2);

    override fun getValue(): Int = value

    object ADAPTER : EnumAdapter<PhoneType>(PhoneType::class.java) {
      override fun fromValue(value: Int): PhoneType? = PhoneType.fromValue(value)
    }

    companion object {
      fun fromValue(value: Int): PhoneType? = values().find { it.value == value }
    }
  }

  data class PhoneNumber(
      val number: String,
      val type: PhoneType? = null,
      val unknownFields: ByteString = okio.ByteString.EMPTY
  ) {
    object ADAPTER : ProtoAdapter<PhoneNumber>(FieldEncoding.LENGTH_DELIMITED, PhoneNumber::class.java) {
      override fun encodedSize(value: PhoneNumber): Int = ProtoAdapter.STRING.encodedSizeWithTag(1, value.number) +
          PhoneType.ADAPTER.encodedSizeWithTag(2, value.type) +
          value.unknownFields.size()

      override fun encode(writer: ProtoWriter, value: PhoneNumber) {
        ProtoAdapter.STRING.encodeWithTag(writer, 1, value.number)
        PhoneType.ADAPTER.encodeWithTag(writer, 2, value.type)
        writer.writeBytes(value.unknownFields)
      }

      override fun decode(reader: ProtoReader): PhoneNumber {
        var number: kotlin.String? = null
        var type: Person.PhoneType? = null
        val unknownFields = reader.decodeMessage {
          tag -> when(tag) {
          1 -> number = ProtoAdapter.STRING.decode(reader)
          2 -> type = PhoneType.ADAPTER.decode(reader)
          else -> UnkownFieldsBuilder.UNKNOWN_FIELD
        }
        }
        return PhoneNumber(
            number = number ?: throw Internal.missingRequiredFields(number, "number"),
            type = type,
            unknownFields = unknownFields)
      }
    }
  }
}
