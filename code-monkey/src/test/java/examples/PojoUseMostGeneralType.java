package examples;

public class PojoUseMostGeneralType {

  public interface Top<T> {

  }

  public interface Left<T, L> extends Top<T> {

    void left(String val);
  }

  public interface Right<T, R> extends Top<T> {

    void right(String val);
  }

  public static class AboveBottom<Q, T> implements Left<T, String>, Right<T, Q> {

    private String left;
    private String right;

    @Override
    public void left(String val) {
      left = val;
    }

    @Override
    public void right(String val) {
      right = val;
    }
  }

  public static class Bottom<T> extends AboveBottom<T, Integer> {

    private final int a;

    public Bottom(int a) {
      this.a = a;
    }
  }

}
