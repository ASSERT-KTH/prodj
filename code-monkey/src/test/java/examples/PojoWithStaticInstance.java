package examples;

public class PojoWithStaticInstance {

  public static PojoWithStaticInstance DIRTY = new PojoWithStaticInstance("Dirty");
  public static PojoWithStaticInstance PAWS = new PojoWithStaticInstance("Paws");

  private final String value;

  private PojoWithStaticInstance(String value) {
    this.value = value;
  }

}
