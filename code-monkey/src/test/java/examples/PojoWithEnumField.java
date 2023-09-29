package examples;

public class PojoWithEnumField {

  private final Peano peano;

  public PojoWithEnumField(Peano peano) {
    this.peano = peano;
  }

  public enum Peano {
    ZERO,
    SUCC
  }
}
