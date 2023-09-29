package se.kth.castor.rockstofetch.util;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import se.kth.castor.rockstofetch.instrument.RecordedMethod;
import java.lang.reflect.Executable;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import spoon.MavenLauncher;
import spoon.MavenLauncher.SOURCE_TYPE;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypedElement;
import spoon.reflect.factory.Factory;
import spoon.reflect.factory.FactoryImpl;
import spoon.reflect.reference.CtTypeReference;
import spoon.support.DefaultCoreFactory;

public class SpoonAccessor {

  private final Factory factory;
  private final LoadingCache<Executable, CtExecutable<?>> executableCache;

  public SpoonAccessor(Path projectPath) {
    String[] sourceClasspath = new MavenLauncher(
        projectPath.toString(), SOURCE_TYPE.ALL_SOURCE, Pattern.compile(".+")
    )
        .getEnvironment()
        .getSourceClasspath();
    MavenLauncher launcher = new MavenLauncher(
        projectPath.toString(), SOURCE_TYPE.APP_SOURCE, Pattern.compile(".+")
    );
    launcher.getEnvironment().setComplianceLevel(17);
    launcher.getEnvironment().setNoClasspath(true);
    launcher.getEnvironment().setAutoImports(false);
    launcher.getEnvironment().setShouldCompile(true);
    launcher.getEnvironment().setSourceClasspath(sourceClasspath);
    launcher.buildModel();

    this.factory = launcher.getFactory();
    Factory shadowFactory = new FactoryImpl(new DefaultCoreFactory(), factory.getEnvironment());
    this.executableCache = CacheBuilder.newBuilder()
        .build(new CacheLoader<>() {
          @Override
          public CtExecutable<?> load(Executable key) {
            return new PartialReflectionTreeBuilder(factory, shadowFactory).asCtMethod(key);
          }
        });
  }

  public Factory getFactory() {
    return factory;
  }

  public CtMethod<?> getMethod(Class<?> receiver, RecordedMethod method) {
    CtType<?> type = factory.Type().get(receiver);
    return Spoons.getCtMethod(factory, type, method);
  }

  public CtTypeReference<?> createTypeReference(Class<?> targetJavaType) {
    return factory.createCtTypeReference(targetJavaType);
  }

  public List<CtTypeReference<?>> getParameterTypes(Executable executable) {
    try {
      CtExecutable<?> ctExecutable = executableCache.get(executable);
      return getParamTypes(ctExecutable.getParameters());
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static List<CtTypeReference<?>> getParamTypes(List<CtParameter<?>> constructor) {
    return (List<CtTypeReference<?>>) (List) constructor
        .stream()
        .map(CtTypedElement::getType)
        .toList();
  }

}
