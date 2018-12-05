package me.dinowernli.grpc.polyglot.protobuf;

import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;

import io.grpc.protobuf.ProtoMethodDescriptorSupplier;

public class LiteralProtoMethodDescriptorSupplier implements ProtoMethodDescriptorSupplier {
  private final MethodDescriptor methodDescriptor;

  private LiteralProtoMethodDescriptorSupplier(MethodDescriptor methodDescriptor) {
    this.methodDescriptor = methodDescriptor;
  }

  public static ProtoMethodDescriptorSupplier forMethodDescriptor(MethodDescriptor methodDescriptor) {
    return new LiteralProtoMethodDescriptorSupplier(methodDescriptor);
  }

  @Override
  public MethodDescriptor getMethodDescriptor() {
    return methodDescriptor;
  }

  @Override
  public ServiceDescriptor getServiceDescriptor() {
    return methodDescriptor.getService();
  }

  @Override
  public FileDescriptor getFileDescriptor() {
    return methodDescriptor.getFile();
  }
}
