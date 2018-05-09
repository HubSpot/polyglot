package me.dinowernli.grpc.polyglot.command;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;

import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import me.dinowernli.grpc.polyglot.grpc.ChannelFactory;
import me.dinowernli.grpc.polyglot.grpc.ServerReflectionClient;
import me.dinowernli.grpc.polyglot.io.Output;
import me.dinowernli.grpc.polyglot.oauth2.OauthCredentialsFactory;
import me.dinowernli.grpc.polyglot.protobuf.ProtocInvoker;
import me.dinowernli.grpc.polyglot.protobuf.ServiceResolver;
import polyglot.ConfigProto.CallConfiguration;
import polyglot.ConfigProto.ProtoConfiguration;

/** Utility to list the services, methods and message definitions for the known GRPC end-points */
public class ServiceList {
  private static final Logger logger = LoggerFactory.getLogger(ServiceCall.class);

  /** Lists the GRPC services - filtered by service name (contains) or method name (contains) */
  public static void listServices(
      Output output,
      ProtoConfiguration protoConfig,
      Optional<String> endpoint,
      Optional<String> serviceFilter,
      Optional<String> methodFilter,
      Optional<Boolean> withMessage,
      CallConfiguration callConfig) {
    HostAndPort hostAndPort = HostAndPort.fromString(endpoint.get());
    ChannelFactory channelFactory = ChannelFactory.create(callConfig);

    logger.info("Creating channel to: " + hostAndPort.toString());
    Channel channel;
    if (callConfig.hasOauthConfig()) {
      channel = channelFactory.createChannelWithCredentials(
          hostAndPort, new OauthCredentialsFactory(callConfig.getOauthConfig()).getCredentials());
    } else {
      channel = channelFactory.createChannel(hostAndPort);
    }

    // Fetch the appropriate file descriptors for the service.
    final FileDescriptorSet fileDescriptorSet;
    Optional<FileDescriptorSet> reflectionDescriptors = Optional.empty();
    if (protoConfig.getUseReflection()) {
      reflectionDescriptors =
          resolveServiceByReflection(channel);
    }

    if (reflectionDescriptors.isPresent()) {
      logger.info("Using proto descriptors fetched by reflection");
      fileDescriptorSet = reflectionDescriptors.get();
    } else {
      try {
        fileDescriptorSet = ProtocInvoker.forConfig(protoConfig).invoke();
        logger.info("Using proto descriptors obtained from protoc");
      } catch (Throwable t) {
        throw new RuntimeException("Unable to resolve service by invoking protoc", t);
      }
    }

    ServiceResolver serviceResolver = ServiceResolver.fromFileDescriptorSet(fileDescriptorSet);

    // Add white-space before the rendered output
    output.newLine();

    for (ServiceDescriptor descriptor : serviceResolver.listServices()) {
      boolean matchingDescriptor =
          !serviceFilter.isPresent()
          || descriptor.getFullName().toLowerCase().contains(serviceFilter.get().toLowerCase());

      if (matchingDescriptor) {
        listMethods(output, protoConfig.getProtoDiscoveryRoot(), descriptor, methodFilter, withMessage);
      }
    }
  }

  /** Lists the methods on the service (the methodFilter will be applied if non-empty)  */
  private static void listMethods(
      Output output,
      String protoDiscoveryRoot,
      ServiceDescriptor descriptor,
      Optional<String> methodFilter,
      Optional<Boolean> withMessage) {

    boolean printedService = false;

    // Due to the way the protos are discovered, the leaf directly of the  protoDiscoveryRoot
    // is the same as the root directory as the proto file
    File protoDiscoveryDir = new File(protoDiscoveryRoot).getParentFile();

    for (MethodDescriptor method : descriptor.getMethods()) {
      if (!methodFilter.isPresent() || method.getName().contains(methodFilter.get())) {

        // Only print the service name once - and only if a method is going to be printed
        if (!printedService) {
          File pFile = new File(protoDiscoveryDir, descriptor.getFile().getName());
          output.writeLine(descriptor.getFullName() + " -> " + pFile.getAbsolutePath());
          printedService = true;
        }

        output.writeLine("  " + descriptor.getFullName() + "/" + method.getName());

        // If requested, add the message definition
        if (withMessage.isPresent() && withMessage.get()) {
          output.writeLine(renderDescriptor(method.getInputType(), "  "));
          output.newLine();
        }
      }
    }

    if (printedService) {
      output.newLine();
    }
  }

  /** Creates a human-readable string to help the user build a message to send to an end-point */
  private static String renderDescriptor(Descriptor descriptor, String indent) {
    if (descriptor.getFields().size() == 0) {
      return indent + "<empty>";
    }

    List<String> fieldsAsStrings = descriptor.getFields().stream()
        .map(field -> renderDescriptor(field, indent + "  "))
        .collect(Collectors.toList());

    return Joiner.on(System.lineSeparator()).join(fieldsAsStrings);
  }

  /** Create a readable string from the field to help the user build a message  */
  private static String renderDescriptor(FieldDescriptor descriptor, String indent) {
    String isOpt = descriptor.isOptional() ? "<optional>" : "<required>";
    String isRep = descriptor.isRepeated() ? "<repeated>" : "<single>";
    String fieldPrefix = indent + descriptor.getJsonName() + "[" + isOpt + " " + isRep + "]";

    if (descriptor.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
      return fieldPrefix + " {" + System.lineSeparator()
          + renderDescriptor(descriptor.getMessageType(), indent + "  ")
          + System.lineSeparator() + indent + "}";

    } else if (descriptor.getJavaType() == FieldDescriptor.JavaType.ENUM) {
      return fieldPrefix + ": " + descriptor.getEnumType().getValues();

    } else {
      return fieldPrefix + ": " + descriptor.getJavaType();
    }
  }

  /**
   * Returns a {@link FileDescriptorSet} describing the supplied service if the remote server
   * advertizes it by reflection. Returns an empty optional if the remote server doesn't support
   * reflection. Throws a NOT_FOUND exception if we determine that the remote server does not
   * support the requested service (but *does* support the reflection service).
   */
  private static Optional<FileDescriptorSet> resolveServiceByReflection(Channel channel) {
    ServerReflectionClient serverReflectionClient = ServerReflectionClient.create(channel);
    ImmutableList<String> services;
    try {
      services = serverReflectionClient.listServices().get();
    } catch (Throwable t) {
      // Listing services failed, try and provide an explanation.
      Throwable root = Throwables.getRootCause(t);
      if (root instanceof StatusRuntimeException) {
        if (((StatusRuntimeException) root).getStatus().getCode() == Status.Code.UNIMPLEMENTED) {
          logger.warn("Could not list services because the remote host does not support " +
              "reflection. To disable resolving services by reflection, either pass the flag " +
              "--use_reflection=false or disable reflection in your config file.");
        }
      }

      // In any case, return an empty optional to indicate that this failed.
      return Optional.empty();
    }

    FileDescriptorSet.Builder builder = FileDescriptorSet.newBuilder();
    for (String serviceName : services) {
      try {
        FileDescriptorSet serviceFileDescriptors = serverReflectionClient.lookupService(serviceName).get();
        builder.addAllFile(serviceFileDescriptors.getFileList());
      } catch (Throwable t) {
        logger.warn("Unable to lookup service by reflection: " + serviceName, t);
        return Optional.empty();
      }
    }

    return Optional.of(builder.build());
  }
}
