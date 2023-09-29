package examples;

public class PojoUsingSameReferenceTwice {

  private final TrivialPojo first;
  private final TrivialPojo second;

  public PojoUsingSameReferenceTwice(TrivialPojo pojo) {
    this.first = pojo;
    this.second = pojo;
  }
}
