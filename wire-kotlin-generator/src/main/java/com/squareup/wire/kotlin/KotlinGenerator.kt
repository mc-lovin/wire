package com.squareup.wire.kotlin

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.DATA
import com.squareup.kotlinpoet.KModifier.FINAL
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.wire.EnumAdapter
import com.squareup.wire.FieldEncoding
import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoReader
import com.squareup.wire.ProtoWriter
import com.squareup.wire.WireEnum
import com.squareup.wire.schema.EnclosingType
import com.squareup.wire.schema.EnumType
import com.squareup.wire.schema.Field
import com.squareup.wire.schema.MessageType
import com.squareup.wire.schema.Options.ENUM_VALUE_OPTIONS
import com.squareup.wire.schema.ProtoFile
import com.squareup.wire.schema.ProtoMember
import com.squareup.wire.schema.ProtoType
import com.squareup.wire.schema.Schema
import com.squareup.wire.schema.Type
import okio.ByteString
import java.util.*

class KotlinGenerator private constructor(
    val schema: Schema,
    private val nameToKotlinName: Map<ProtoType, ClassName>,
    val emitAndroid: Boolean
) {
  /** Returns the Kotlin type for [protoType]. */
  fun typeName(protoType: ProtoType) = nameToKotlinName[protoType]

  /** Returns the full name of the class generated for `type`.  */
  fun generatedTypeName(type: Type) = typeName(type.type())

  fun generateType(type: Type): TypeSpec = when (type) {
    is MessageType -> generateMessage(type)
    is EnumType -> generateEnum(type)
    is EnclosingType -> generateEnclosingType(type)
    else -> error("Unknown type $type")
  }

  private fun generateMessage(type: MessageType): TypeSpec {
    val className = type.type().simpleName()

    val adapterClassName = ClassName("", className, className + "_ADAPTER")

    val compaionObjectBuilder = TypeSpec.companionObjectBuilder()
        .addProperty(PropertySpec.builder("ADAPTER", adapterClassName)
            .addAnnotation(AnnotationSpec.builder(JvmField::class).build())
            .initializer(adapterClassName.toString() + "()")
            .build())
        .build()

    val classBuilder = TypeSpec.classBuilder(className)
        .addModifiers(DATA)
        .addType(generateAdapter(type)) // Maybe this should go to the companion object
        .addType(compaionObjectBuilder)

    addMessageConstructor(type, classBuilder)

    type.nestedTypes().forEach { it -> classBuilder.addType(generateType(it)) }

    return classBuilder.build()
  }

  private fun generateAdapter(type: MessageType): TypeSpec {
    val className = type.type().simpleName()

    val adapterClassName = ClassName("", className, className + "_ADAPTER")

    val parentClassName = nameToKotlinName[type.type()]!!

    return TypeSpec.classBuilder(adapterClassName.simpleName)
        .superclass(ProtoAdapter::class.asClassName().parameterizedBy(parentClassName))
        .addSuperclassConstructorParameter("%L.%L", FieldEncoding::class.asClassName(),
            "LENGTH_DELIMITED")
        .addSuperclassConstructorParameter("%L::class.java", parentClassName)
        .addFunction(encodedSizeFunc(type))
        .addFunction(encodeFunc(type))
        .addFunction(decodeFunc(type))
        .build()
  }

  private fun decodeFunc(message: MessageType): FunSpec {
    val className = nameToKotlinName[message.type()]!!

    var body = CodeBlock.builder()
    var returnBody = CodeBlock.builder()
    var decodeBlock = CodeBlock.builder()
    decodeBlock.beginControlFlow("val unknownFields = reader.decodeMessage")
    decodeBlock.addStatement("tag->")
    decodeBlock.beginControlFlow("when(tag)")
    returnBody.add("return %L(\n", className.simpleName)

    // Declarations.
    message.fields().forEach { field ->
      val adapterName = getAdapterName(field)
      if (field.isRepeated) {
        body.addStatement("var %L = mutableListOf<%L>()", field.name(),
            nameToKotlinName[field.type()]!!)
        returnBody.add("%L = %L,\n", field.name(), field.name())
        decodeBlock.addStatement("%L -> %L.add(%L.decode(reader))", field.tag(), field.name(),
            adapterName)
      } else {
        body.addStatement("var %L: %L = null", field.name(),
            nameToKotlinName[field.type()]!!.asNullable())
        decodeBlock.addStatement("%L -> %L = %L.decode(reader)", field.tag(), field.name(),
            adapterName)

        if (field.isOptional) {
          returnBody.add("%L = %L,\n", field.name(), field.name())
        } else {
          returnBody.add("%L = %L ?: throw %L(%L, \"%L\"),\n",
              field.name(), field.name(),
              "com.squareup.wire.internal.Internal.missingRequiredFields", field.name(),
              field.name())
        }
      }
    }

    decodeBlock.addStatement("else -> %L", "com.squareup.wire.kotlin." +
        "UnkownFieldsBuilder.Companion.UNKNOWN_FIELD")
    decodeBlock.endControlFlow()
    decodeBlock.endControlFlow()

    returnBody.add("unknownFields = unknownFields)\n")

    return FunSpec.builder("decode")
        .addParameter("reader", ProtoReader::class.asClassName())
        .returns(className)
        .addCode(body.build())
        .addCode(decodeBlock.build())
        .addCode(returnBody.build())
        .addModifiers(OVERRIDE)
        .build()
  }

  private fun getAdapterName(field: Field): CodeBlock {
    if (field.type().isScalar) {
      return CodeBlock.of("%L.%L", ProtoAdapter::class.asClassName(),
          field.type().simpleName().toUpperCase(Locale.US))
    }
    return CodeBlock.of("%L.ADAPTER", nameToKotlinName[field.type()]!!.simpleName)
  }

  private fun encodeFunc(message: MessageType): FunSpec {
    val className = nameToKotlinName[message.type()]!!
    var body = CodeBlock.builder()

    message.fields().forEach { field ->
      if (field.type().isScalar) {
        body.addStatement("%L.%L.encodeWithTag(writer, %L, value.%L)",
            ProtoAdapter::class.asClassName(),
            field.type().simpleName().toUpperCase(Locale.US),
            field.tag(),
            field.name())
      } else {
        body.addStatement("%L.ADAPTER.%LencodeWithTag(writer, %L, value.%L)",
            nameToKotlinName[field.type()]!!.simpleName,
            if (field.isRepeated) "asRepeated()." else "",
            field.tag(),
            field.name())
      }
    }

    body.addStatement("writer.writeBytes(value.unknownFields)")

    return FunSpec.builder("encode")
        .addParameter("writer", ProtoWriter::class.asClassName())
        .addParameter("value", className)
        .addCode(body.build())
        .addModifiers(OVERRIDE)
        .build()
  }

  private fun encodedSizeFunc(message: MessageType): FunSpec {
    val className = nameToKotlinName[message.type()]!!

    var body = CodeBlock.builder()
        .add("return ")

    message.fields().forEach { field ->
      if (field.type().isScalar) {
        body.add("%L.%L.encodedSizeWithTag(%L, value.%L) + \n",
            ProtoAdapter::class.asClassName(),
            field.type().simpleName().toUpperCase(Locale.US),
            field.tag(),
            field.name())
      } else {
        body.add("%L.ADAPTER.%LencodedSizeWithTag(%L, value.%L) + \n",
            nameToKotlinName[field.type()]!!.simpleName,
            if (field.isRepeated) "asRepeated()." else "",
            field.tag(),
            field.name())
      }
    }

    return FunSpec.builder("encodedSize")
        .addParameter("value", className)
        .returns(Int::class.asTypeName())
        .addCode(body
            .add("value.unknownFields.size()")
            .build())
        .addModifiers(OVERRIDE)
        .build()
  }

  private fun addMessageConstructor(message: MessageType, classBuilder: TypeSpec.Builder): FunSpec {
    val constructorBuilder = FunSpec.constructorBuilder()
    message.fields().forEach { field ->
      val type = field.type()
      var kotlinType: ClassName = nameToKotlinName[type]!!
      val fieldName = field.name()

      when {
        field.isOptional -> {
          constructorBuilder.addParameter(ParameterSpec.builder(fieldName, kotlinType.asNullable())
              .defaultValue("null")
              .build())
          classBuilder.addProperty(PropertySpec.builder(fieldName, kotlinType.asNullable())
              .initializer(fieldName)
              .build())
        }

        field.isRepeated -> {
          constructorBuilder.addParameter(fieldName,
              List::class.asClassName().parameterizedBy(kotlinType))
          classBuilder.addProperty(PropertySpec.builder(fieldName,
              List::class.asClassName().parameterizedBy(kotlinType))
              .initializer(fieldName)
              .build())
        }
        else -> {
          constructorBuilder.addParameter(fieldName, kotlinType)
          classBuilder.addProperty(PropertySpec.builder(fieldName, kotlinType)
              .initializer(fieldName)
              .build())
        }
      }
    }

    constructorBuilder.addParameter(
        ParameterSpec.builder("unknownFields", ByteString::class.asClassName())
            .defaultValue("%L.EMPTY", ByteString::class.asClassName())
            .build())
    classBuilder.addProperty(PropertySpec.builder("unknownFields", ByteString::class.asClassName())
        .initializer("unknownFields")
        .build())

    classBuilder.primaryConstructor(constructorBuilder.build())

    return constructorBuilder.build()
  }

  private fun generateEnum(type: EnumType): TypeSpec {
    val className = nameToKotlinName[type.type()]!!
    val adapterClassName = ClassName("", className.simpleName, className.simpleName + "_ADAPTER")

    var builder = TypeSpec.enumBuilder(type.type().simpleName())
        .addSuperinterface(WireEnum::class)
        .addProperty(PropertySpec.builder("value", Int::class, PRIVATE)
            .initializer("value")
            .build())
        .primaryConstructor(FunSpec.constructorBuilder()
            .addParameter("value", Int::class, PRIVATE)
            .build())
        .addFunction(FunSpec.builder("getValue")
            .returns(Int::class)
            .addModifiers(OVERRIDE)
            .addStatement("return value")
            .build())

        .addType(generateEnumAdapter(type))
        .addType(TypeSpec.companionObjectBuilder()
            .addProperty(PropertySpec.builder("ADAPTER", adapterClassName)
                .addAnnotation(AnnotationSpec.builder(JvmField::class).build())
                .initializer(adapterClassName.toString() + "()")
                .build())
            .addFunction(FunSpec.builder("fromValue")
                .addParameter("value", Int::class)
                .returns(className.asNullable())
                .addStatement("return values().find { it.value == value } ")
                .build())
            .build())

    type.constants().forEach { constant ->
      builder.addEnumConstant(constant.name(), TypeSpec.anonymousClassBuilder()
          .addSuperclassConstructorParameter("%L", constant.tag())
          .build())
    }

    return builder.build()
  }

  private fun generateEnumAdapter(enum: EnumType): TypeSpec {
    val className = ClassName("", enum.type().simpleName(), enum.type().simpleName() + "_ADAPTER")
    val parentClassName = nameToKotlinName[enum.type()]!!
    return TypeSpec.classBuilder(className.simpleName)
        .superclass(EnumAdapter::class.asClassName().parameterizedBy(parentClassName))
        .addSuperclassConstructorParameter("%L::class.java", parentClassName)
        .addFunction(FunSpec.builder("fromValue")
            .addModifiers(OVERRIDE)
            .addParameter("value", Int::class)
            .returns(parentClassName.asNullable())
            .addStatement("return %L.fromValue(value)", parentClassName)
            .build())
        .build()
  }


  private fun generateEnclosingType(type: EnclosingType): TypeSpec {
    val kotlinType = requireNotNull(typeName(type.type())) { "Unknown type $type" }

    val builder = TypeSpec.classBuilder(kotlinType.simpleName)
        .addModifiers(FINAL)

    var documentation = type.documentation()
    if (!documentation.isEmpty()) {
      documentation += "\n\n<p>"
    }
    documentation += "<b>NOTE:</b> This type only exists to maintain class structure" + " for its nested types and is not an actual message."
    builder.addKdoc("%L\n", documentation)

    builder.primaryConstructor(FunSpec.constructorBuilder()
        .addModifiers(PRIVATE)
        .addStatement("throw new \$T()", AssertionError::class)
        .build())

    for (nestedType in type.nestedTypes()) {
      builder.addType(generateType(nestedType))
    }

    return builder.build()
  }

  companion object {
    private val MESSAGE = Message::class.asClassName()
    private val ANDROID_MESSAGE = MESSAGE.peerClass("AndroidMessage")
    private val MESSAGE_BUILDER = Message.Builder::class.asClassName()

    private val ENUM_DEPRECATED = ProtoMember.get(ENUM_VALUE_OPTIONS, "deprecated")

    private val BUILT_IN_TYPES = mapOf(
        ProtoType.BOOL to BOOLEAN,
        ProtoType.BYTES to ByteString::class.asClassName(),
        ProtoType.DOUBLE to DOUBLE,
        ProtoType.FLOAT to FLOAT,
        ProtoType.FIXED32 to INT,
        ProtoType.FIXED64 to LONG,
        ProtoType.INT32 to INT,
        ProtoType.INT64 to LONG,
        ProtoType.SFIXED32 to INT,
        ProtoType.SFIXED64 to LONG,
        ProtoType.SINT32 to INT,
        ProtoType.SINT64 to LONG,
        ProtoType.STRING to String::class.asClassName(),
        ProtoType.UINT32 to INT,
        ProtoType.UINT64 to LONG
    )

    @JvmStatic @JvmName("get")
    operator fun invoke(schema: Schema, emitAndroid: Boolean): KotlinGenerator {
      val map = BUILT_IN_TYPES.toMutableMap()

      fun putAll(kotlinPackage: String, enclosingClassName: ClassName?, types: List<Type>) {
        for (type in types) {
          val className = enclosingClassName?.nestedClass(type.type().simpleName())
              ?: ClassName(kotlinPackage, type.type().simpleName())
          map[type.type()] = className
          putAll(kotlinPackage, className, type.nestedTypes())
        }
      }

      for (protoFile in schema.protoFiles()) {
        val kotlinPackage = protoFile.kotlinPackage()
        putAll(kotlinPackage, null, protoFile.types())

        for (service in protoFile.services()) {
          val className = ClassName(kotlinPackage, service.type().simpleName())
          map[service.type()] = className
        }
      }

      return KotlinGenerator(schema, map, emitAndroid)
    }
  }
}

private fun ProtoFile.kotlinPackage() = javaPackage() ?: packageName() ?: ""