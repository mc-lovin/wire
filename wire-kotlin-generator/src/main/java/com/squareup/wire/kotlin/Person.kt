import android.os.Parcel
import android.os.Parcelable
import com.squareup.wire.EnumAdapter
import com.squareup.wire.FieldEncoding
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoReader
import com.squareup.wire.ProtoWriter
import com.squareup.wire.WireEnum
import com.squareup.wire.internal.Internal
import com.squareup.wire.kotlin.UnkownFieldsBuilder
import com.squareup.wire.kotlin.decodeMessage
import kotlin.Array
import kotlin.Int
import kotlin.String
import kotlin.collections.List
import okio.ByteString

data class Person(
    val name: String,
    val id: Int,
    val email: String? = null,
    val phone: List<PhoneNumber> = emptyList(),
    val unknownFields: ByteString = ByteString.EMPTY
) : Parcelable {
  override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeByteArray(ADAPTER.encode(this))

  override fun describeContents() = 0

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
      var name: String? = null
      var id: Int? = null
      var email: String? = null
      var phone = mutableListOf<PhoneNumber>()
      val unknownFields = reader.decodeMessage { tag ->
        when (tag) {
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
          unknownFields = unknownFields
      )
    }
  }

  object CREATOR : Parcelable.Creator<Person> {
    override fun createFromParcel(input: Parcel) = ADAPTER.decode(input.createByteArray())

    override fun newArray(size: Int): Array<Person?> = arrayOfNulls(size)
  }

  enum class PhoneType(private val value: Int) : WireEnum {
    HOME(0),

    value_(1),

    WORK(2);

    override fun getValue(): Int = value

    object ADAPTER : EnumAdapter<PhoneType>(PhoneType::class.java) {
      override fun fromValue(value: Int): PhoneType? = values().find { it.value == value }
    }
  }

  data class PhoneNumber(
      val number: String,
      val type: PhoneType = PhoneType.HOME,
      val unknownFields: ByteString = ByteString.EMPTY
  ) : Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeByteArray(ADAPTER.encode(this))

    override fun describeContents() = 0

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
        var number: String? = null
        var type: PhoneType = PhoneType.HOME
        val unknownFields = reader.decodeMessage { tag ->
          when (tag) {
            1 -> number = ProtoAdapter.STRING.decode(reader)
            2 -> type = PhoneType.ADAPTER.decode(reader)
            else -> UnkownFieldsBuilder.UNKNOWN_FIELD
          }
        }
        return PhoneNumber(
            number = number ?: throw Internal.missingRequiredFields(number, "number"),
            type = type,
            unknownFields = unknownFields
        )
      }
    }

    object CREATOR : Parcelable.Creator<PhoneNumber> {
      override fun createFromParcel(input: Parcel) = ADAPTER.decode(input.createByteArray())

      override fun newArray(size: Int): Array<PhoneNumber?> = arrayOfNulls(size)
    }
  }
}