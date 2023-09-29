package se.kth.castor.pankti.codemonkey.serialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import examples.FailingPojoWithTwoDistinctConstructors;
import examples.PojoCollectionDifferentFieldType;
import examples.PojoConstructorIsNotCopyConstructor;
import examples.PojoCopiesInputCollection;
import examples.PojoUseMostGeneralType;
import examples.PojoUsingSameReferenceTwice;
import examples.PojoWithComplexField;
import examples.PojoWithEnumField;
import examples.PojoWithEnumField.Peano;
import examples.PojoWithFactoryMethod;
import examples.PojoWithFactoryMethodAndField;
import examples.PojoWithFieldWrite;
import examples.PojoWithMultipleSetter;
import examples.PojoWithOnlyFieldWrites;
import examples.PojoWithReferenceEquality;
import examples.PojoWithSetter;
import examples.PojoWithSetterTouchingMultipleFields;
import examples.PojoWithShortAndByteLiterals;
import examples.PojoWithStaticInstance;
import examples.SimplePojoWithInheritance;
import examples.TrivialPojo;
import java.io.File;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import se.kth.castor.pankti.codemonkey.construction.solving.ClassConstructionSolver;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationBindConstructorParameter;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationCallDefaultConstructor;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationCallSetter;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationCallSimpleFactoryMethod;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationSetField;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationStrategy;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationUseEnumConstant;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationUseStandardCharset;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationUseStaticFieldInstance;
import se.kth.castor.pankti.codemonkey.construction.solving.SolvingState;
import se.kth.castor.pankti.codemonkey.util.SerializationFailedException;
import se.kth.castor.pankti.codemonkey.util.SolveFailedException;
import spoon.Launcher;
import spoon.reflect.code.CtStatement;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

class SerializerTest {

  private List<MutationStrategy> mutations;
  private Serializer serializer;
  private Factory factory;

  @BeforeEach
  void setUp() {
    Launcher launcher = new Launcher();
    launcher.getEnvironment().setComplianceLevel(17);
    launcher.getEnvironment().setAutoImports(true);
    launcher.addInputResource("src/test/java/examples");
    launcher.buildModel();
    factory = launcher.getFactory();

    mutations = List.of(
        new MutationBindConstructorParameter(),
        new MutationCallDefaultConstructor(),
        new MutationCallSetter(),
        new MutationCallSimpleFactoryMethod(),
        new MutationUseEnumConstant(),
        new MutationSetField(),
        new MutationUseStaticFieldInstance(),
        new MutationUseStandardCharset()
    );

    serializer = new Serializer(
        factory,
        ClassConstructionSolver.cached((ctClass, instance) -> new ClassConstructionSolver(
            mutations,
            SolvingState.constructType(ctClass),
            null
        )),
        UnknownActionHandler.fail()
    );
  }

  @Test
  void testTrivialPojo() throws SerializationFailedException {
    TrivialPojo pojo = new TrivialPojo("Hello", "World");
    List<CtStatement> statements = serializeToMyself(pojo);

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            TrivialPojo myself = new TrivialPojo("Hello", "World");
        }"""
    );
  }

  @Test
  void testPojoWithOnlyFieldWrites() throws SerializationFailedException {
    PojoWithOnlyFieldWrites pojo = new PojoWithOnlyFieldWrites();
    pojo.firstName = "Hello";
    pojo.lastName = "World";
    List<CtStatement> statements = serializeToMyself(pojo);

    assertThat(statements).hasSize(3);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            PojoWithOnlyFieldWrites myself = new PojoWithOnlyFieldWrites();
            myself.firstName = "Hello";
            myself.lastName = "World";
        }"""
    );
  }

  @Test
  void testPojoWithFieldWrite() throws SerializationFailedException {
    PojoWithFieldWrite pojo = new PojoWithFieldWrite("Hello");
    pojo.lastName = "World";
    List<CtStatement> statements = serializeToMyself(pojo);

    assertThat(statements).hasSize(2);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            PojoWithFieldWrite myself = new PojoWithFieldWrite("Hello");
            myself.lastName = "World";
        }"""
    );
  }

  @Test
  void testPojoWithSetter() throws SerializationFailedException {
    PojoWithSetter pojo = new PojoWithSetter("Hello");
    pojo.setLastName("World");
    List<CtStatement> statements = serializeToMyself(pojo);

    assertThat(statements).hasSize(2);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            PojoWithSetter myself = new PojoWithSetter("Hello");
            myself.setLastName("World");
        }"""
    );
  }

  @Test
  void testPojoWithFactory() throws SerializationFailedException {
    PojoWithFactoryMethod pojo = PojoWithFactoryMethod.create("Hello", "World");
    List<CtStatement> statements = serializeToMyself(pojo);

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            PojoWithFactoryMethod myself = PojoWithFactoryMethod.create("Hello", "World");
        }"""
    );
  }

  @Test
  void testPojoWithFactoryAndField() throws SerializationFailedException {
    PojoWithFactoryMethodAndField pojo = PojoWithFactoryMethodAndField.create("Hello");
    pojo.lastName = "World";
    List<CtStatement> statements = serializeToMyself(pojo);

    assertThat(statements).hasSize(2);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            PojoWithFactoryMethodAndField myself = PojoWithFactoryMethodAndField.create("Hello");
            myself.lastName = "World";
        }"""
    );
  }

  @Test
  void testPojoWithComplexField() throws SerializationFailedException {
    PojoWithComplexField pojo = new PojoWithComplexField(new TrivialPojo("Hello", "World"));
    List<CtStatement> statements = serializeToMyself(pojo);

    assertThat(statements).hasSize(2);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            TrivialPojo trivial = new TrivialPojo("Hello", "World");
            PojoWithComplexField myself = new PojoWithComplexField(trivial);
        }"""
    );
  }

  @Test
  void testPojoUsingSameReferenceTwice() throws SerializationFailedException {
    TrivialPojo trivial = new TrivialPojo("Hello", "World");
    PojoUsingSameReferenceTwice pojo = new PojoUsingSameReferenceTwice(trivial);
    List<CtStatement> statements = serializeToMyself(pojo);

    assertThat(statements).hasSize(2);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            TrivialPojo first = new TrivialPojo("Hello", "World");
            PojoUsingSameReferenceTwice myself = new PojoUsingSameReferenceTwice(first);
        }"""
    );
  }

  @Test
  void testPojoWithReferenceEquality() throws SerializationFailedException {
    TrivialPojo trivial = new TrivialPojo("Hello", "World");
    PojoWithReferenceEquality pojo = new PojoWithReferenceEquality(trivial, trivial);
    List<CtStatement> statements = serializeToMyself(pojo);

    assertThat(statements).hasSize(2);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            TrivialPojo first = new TrivialPojo("Hello", "World");
            PojoWithReferenceEquality myself = new PojoWithReferenceEquality(first, first);
        }"""
    );
  }

  @Test
  void testPojoWithEnumField() throws SerializationFailedException {
    PojoWithEnumField pojo = new PojoWithEnumField(Peano.ZERO);
    List<CtStatement> statements = serializeToMyself(pojo);

    assertThat(statements).hasSize(2);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            PojoWithEnumField.Peano peano = Peano.ZERO;
            PojoWithEnumField myself = new PojoWithEnumField(peano);
        }"""
    );
  }

  @Test
  void testEnum() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(Peano.ZERO);

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            PojoWithEnumField.Peano myself = Peano.ZERO;
        }"""
    );
  }

  @ParameterizedTest
  @ValueSource(strings = {"DIRTY", "PAWS"})
  void testPojoWithStaticInstance(String field) throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        field.equals("DIRTY") ? PojoWithStaticInstance.DIRTY : PojoWithStaticInstance.PAWS
    );

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            PojoWithStaticInstance myself = PojoWithStaticInstance.%s;
        }""".formatted(field)
    );
  }

  @Test
  void testIntArray() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        new int[]{1, 2, 3}
    );

    assertThat(statements).hasSize(4);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            int myself_0 = 1;
            int myself_1 = 2;
            int myself_2 = 3;
            int[] myself = { myself_0, myself_1, myself_2 };
        }"""
    );
  }

  @Test
  void testEmptyIntArray() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        new int[]{}
    );

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            int[] myself = {  };
        }"""
    );
  }

  @Test
  void testIntArrayList() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        new ArrayList<>(List.of(1, 2, 3)),
        factory.createCtTypeReference(ArrayList.class)
            .setActualTypeArguments(List.of(factory.Type().integerType()))
    );

    assertThat(statements).hasSize(4);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            int myself_0 = 1;
            int myself_1 = 2;
            int myself_2 = 3;
            ArrayList<Integer> myself = new java.util.ArrayList<>(java.util.List.of(myself_0, myself_1, myself_2));
        }"""
    );
  }

  @Test
  void testEmptyArrayList() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        new ArrayList<>(),
        factory.createCtTypeReference(ArrayList.class)
            .setActualTypeArguments(List.of(factory.Type().stringType()))
    );

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            ArrayList<String> myself = new java.util.ArrayList<>(java.util.List.of());
        }"""
    );
  }

  @Test
  void testIntImmutableList() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        List.of(1, 2, 3),
        factory.createCtTypeReference(List.class)
            .setActualTypeArguments(List.of(factory.Type().integerType()))
    );

    assertThat(statements).hasSize(4);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            int myself_0 = 1;
            int myself_1 = 2;
            int myself_2 = 3;
            List<Integer> myself = java.util.List.of(myself_0, myself_1, myself_2);
        }"""
    );
  }

  @Test
  void testEmptyImmutableList() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(List.of(), factory
        .createCtTypeReference(List.class)
        .setActualTypeArguments(List.of(factory.Type().stringType())));

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            List<String> myself = java.util.List.of();
        }"""
    );
  }

  @Test
  void testSimplePojoWithInheritanceUsesGeneralType() throws SerializationFailedException {
    SimplePojoWithInheritance.Subclass pojo = new SimplePojoWithInheritance.Subclass("hey", 20, 42);
    pojo.setValueSetter("hello");
    List<CtStatement> statements = serializeToMyself(
        pojo, factory.createCtTypeReference(SimplePojoWithInheritance.class)
    );

    assertThat(statements).hasSize(2);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            SimplePojoWithInheritance myself = new SimplePojoWithInheritance.Subclass("hey", 20, 42);
            myself.setValueSetter("hello");
        }"""
    );
  }

  @Test
  void testEmptyOptional() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        Optional.empty(),
        factory.createCtTypeReference(Optional.class)
            .setActualTypeArguments(List.of(factory.Type().stringType()))
    );

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            Optional<String> myself = java.util.Optional.empty();
        }"""
    );
  }

  @Test
  void testOptionalWithValue() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        Optional.of(20),
        factory.createCtTypeReference(Optional.class)
            .setActualTypeArguments(List.of(factory.Type().integerType()))
    );

    assertThat(statements).hasSize(2);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            int myself_0 = 20;
            Optional<Integer> myself = java.util.Optional.of(myself_0);
        }"""
    );
  }

  @Test
  void testFailingPojo() {
    assertThatThrownBy(
        () -> serializeToMyself(new FailingPojoWithTwoDistinctConstructors("hello"))
    )
        .isInstanceOf(SolveFailedException.class);
  }

  @Test
  void testPojoCopiesInputCollection() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(new PojoCopiesInputCollection(
        List.of("hello", "there"),
        Set.of("a", "set"),
        Map.of("Wer a sagt...", 42)
    ));

    assertThat(statements).hasSize(11);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            String list_0 = "hello";
            String list_1 = "there";
            List<String> list = new java.util.ArrayList<>(java.util.List.of(list_0, list_1));
            String collection_0 = "a";
            String collection_1 = "set";
            Collection<String> collection = new java.util.HashSet<>(java.util.Set.of(collection_0, collection_1));
            String map_0_key = "Wer a sagt...";
            int map_0_value = 42;
            Map.Entry<String, Integer> map_0 = Map.entry(map_0_key, map_0_value);
            Map<String, Integer> map = new java.util.HashMap<>(java.util.Map.ofEntries(map_0));
            PojoCopiesInputCollection myself = new PojoCopiesInputCollection(list, collection, map);
        }"""
    );
  }

  @Test
  void testPojoConstructorIsNotCopyConstructor() {
    assertThatThrownBy(
        () -> serializeToMyself(new PojoConstructorIsNotCopyConstructor(42))
    )
        .isInstanceOf(SerializationFailedException.class);
  }

  @Test
  void testPojoDifferentFieldType() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(new PojoCollectionDifferentFieldType(
        Map.of("a", "b"), Set.of("c"), List.of("d")
    ));

    assertThat(statements).hasSize(9);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            String map_0_key = "a";
            String map_0_value = "b";
            Map.Entry<String, String> map_0 = Map.entry(map_0_key, map_0_value);
            LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>(java.util.Map.ofEntries(map_0));
            String set_0 = "c";
            LinkedHashSet<String> set = new java.util.LinkedHashSet<>(java.util.Set.of(set_0));
            String list_0 = "d";
            LinkedList<String> list = new java.util.LinkedList<>(java.util.List.of(list_0));
            PojoCollectionDifferentFieldType myself = new PojoCollectionDifferentFieldType(map, set, list);
        }"""
    );
  }

  @Test
  void testPojoUseMostGeneralType() throws SerializationFailedException {
    var pojo = new PojoUseMostGeneralType.Bottom<>(42);
    pojo.left("hello");
    pojo.right("there");
    List<CtStatement> statements = serializeToMyself(
        pojo,
        factory.createCtTypeReference(PojoUseMostGeneralType.Right.class)
            .setActualTypeArguments(List.of(
                factory.Type().stringType(),
                factory.Type().integerType()
            ))
    );

    assertThat(statements).hasSize(3);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            PojoUseMostGeneralType.AboveBottom<Integer, String> myself = new PojoUseMostGeneralType.Bottom<>(42);
            myself.left("hello");
            myself.right("there");
        }"""
    );
  }

  @Test
  void testStringBuilder() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(new StringBuilder("hello").append(" friends"));

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            StringBuilder myself = new StringBuilder("hello friends");
        }"""
    );
  }

  @Test
  void testClass() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        SerializerTest.class,
        factory.createCtTypeReference(Class.class)
            .addActualTypeArgument(factory.createWildcardReference())
    );

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            Class<?> myself = SerializerTest.class;
        }"""
    );
  }

  @Test
  void testLinkedListQueue() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        new LinkedList<>(List.of("1", "2")),
        factory.Type()
            .get(Queue.class)
            .getReference()
            .addActualTypeArgument(factory.Type().stringType())
    );

    assertThat(statements).hasSize(3);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            String myself_0 = "1";
            String myself_1 = "2";
            Queue<String> myself = new java.util.LinkedList<>(java.util.List.of(myself_0, myself_1));
        }"""
    );
  }

  @Test
  void testArrayDequeueQueue() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        new ArrayDeque<>(List.of("1", "2")),
        factory.Type()
            .get(Queue.class)
            .getReference()
            .addActualTypeArgument(factory.Type().stringType())
    );

    assertThat(statements).hasSize(3);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            String myself_0 = "1";
            String myself_1 = "2";
            Queue<String> myself = new java.util.ArrayDeque<>(java.util.List.of(myself_0, myself_1));
        }"""
    );
  }

  @ParameterizedTest
  @MethodSource("standardCharsetsArgumentSource")
  void testStandardCharset(Charset charset, String name) throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        charset,
        factory.createCtTypeReference(Charset.class)
    );

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            Charset myself = StandardCharsets.%s;
        }""".formatted(name)
    );
  }

  private static Stream<Arguments> standardCharsetsArgumentSource() {
    return Arrays.stream(StandardCharsets.class.getFields())
        .filter(it -> Charset.class.isAssignableFrom(it.getType()))
        .filter(it -> Modifier.isStatic(it.getModifiers()))
        .map(field -> {
          try {
            return Arguments.of(field.get(null), field.getName());
          } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void testFile() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(new File("hello/world/there.txt"));

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            File myself = new File("hello/world/there.txt");
        }"""
    );
  }

  @Test
  void testPath() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(Path.of("hello/world/there.txt"));

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            Path myself = Path.of("hello/world/there.txt");
        }"""
    );
  }

  @Test
  void testPojoWithShortAndByteLiterals() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        new PojoWithShortAndByteLiterals((byte) 21, (short) 21)
    );

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            PojoWithShortAndByteLiterals myself = new PojoWithShortAndByteLiterals(((byte) (21)), ((short) (21)));
        }"""
    );
  }

  @Test
  void testBigDecimal() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        new BigDecimal("1.3")
    );

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            BigDecimal myself = new BigDecimal("1.3");
        }"""
    );
  }

  @Test
  void testBigInteger() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        new BigInteger("133333333333333333333333333333333333337")
    );

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            BigInteger myself = new BigInteger("133333333333333333333333333333333333337");
        }"""
    );
  }

  @Test
  void testLocale() throws SerializationFailedException {
    List<CtStatement> statements = serializeToMyself(
        Locale.GERMANY
    );

    assertThat(statements).hasSize(1);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            Locale myself = new Locale("de", "DE", "");
        }"""
    );
  }

  @Test
  void testPojoWithMultipleSetter() throws SerializationFailedException {
    PojoWithMultipleSetter pojo = new PojoWithMultipleSetter();
    pojo.setA(1L);
    pojo.setB(2);
    pojo.setC('c');
    List<CtStatement> statements = serializeToMyself(pojo);

    assertThat(statements).hasSize(7);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            PojoWithMultipleSetter myself = new PojoWithMultipleSetter();
            long a = 1L;
            myself.setA(a);
            int b = 2;
            myself.setB(b);
            char c = 'c';
            myself.setC(c);
        }"""
    );
  }

  @Test
  void testPojoWithSetterTouchingMultipleFields() throws SerializationFailedException {
    PojoWithSetterTouchingMultipleFields pojo = new PojoWithSetterTouchingMultipleFields();
    pojo.setValues(1, 2, 3);
    List<CtStatement> statements = serializeToMyself(pojo);

    assertThat(statements).hasSize(2);
    assertThat(getStatementsAsString(statements)).isEqualTo("""
        {
            PojoWithSetterTouchingMultipleFields myself = new PojoWithSetterTouchingMultipleFields();
            myself.setValues(1, 2, 3);
        }"""
    );
  }

  private List<CtStatement> serializeToMyself(Object pojo) throws SerializationFailedException {
    return serializer.serialize(
        null,
        pojo,
        "myself",
        factory.createCtTypeReference(pojo.getClass())
    ).statements();
  }

  private List<CtStatement> serializeToMyself(
      Object pojo,
      CtTypeReference<?> type
  ) throws SerializationFailedException {
    return serializer.serialize(
        null,
        pojo,
        "myself",
        type
    ).statements();
  }

  private String getStatementsAsString(List<CtStatement> statements) {
    return factory.createBlock().setStatements(statements).toString();
  }
}
