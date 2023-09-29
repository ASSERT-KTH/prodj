package examples;

public class SimplePojoWithInheritance {

  private String valueSetter;
  private final String valueConstructor;

  public SimplePojoWithInheritance(String valueConstructor) {
    this.valueConstructor = valueConstructor;
  }

  public void setValueSetter(String valueSetter) {
    this.valueSetter = valueSetter;
  }

  public String getValueSetter() {
    return valueSetter;
  }

  public static class Subclass extends SimplePojoWithInheritance {

    private final int innerInt;
    private int secondInt;

    public Subclass(String a, int b, int c) {
      this(b, a);
      this.secondInt = c;
    }

    public Subclass(int c, String d) {
      super(d);
      this.innerInt = c;
    }
  }
}
