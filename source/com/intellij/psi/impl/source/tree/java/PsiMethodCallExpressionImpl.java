package com.intellij.psi.impl.source.tree.java;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.impl.DebugUtil;
import com.intellij.psi.impl.PsiImplUtil;
import com.intellij.psi.impl.source.resolve.JavaResolveCache;
import com.intellij.psi.impl.source.tree.ChildRole;
import com.intellij.psi.impl.source.tree.ElementType;
import com.intellij.psi.impl.source.tree.JavaElementType;
import com.intellij.psi.impl.source.tree.TreeUtil;
import com.intellij.psi.tree.ChildRoleBase;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.Function;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class PsiMethodCallExpressionImpl extends ExpressionPsiElement implements PsiMethodCallExpression {
  private static final Logger LOG = Logger.getInstance("#com.intellij.psi.impl.source.tree.java.PsiMethodCallExpressionImpl");
  @NonNls private static final String GET_CLASS_METHOD = "getClass";

  public PsiMethodCallExpressionImpl() {
    super(JavaElementType.METHOD_CALL_EXPRESSION);
  }

  public PsiType getType() {
    return JavaResolveCache.getInstance(getProject()).getType(this, ourTypeEvaluator);
  }

  public PsiMethod resolveMethod() {
    return (PsiMethod)getMethodExpression().resolve();
  }

  @NotNull
  public JavaResolveResult resolveMethodGenerics() {
    return getMethodExpression().advancedResolve(false);
  }

  @NotNull
  public PsiReferenceParameterList getTypeArgumentList() {
    PsiReferenceExpression expression = getMethodExpression();
    PsiReferenceParameterList result = expression.getParameterList();
    if (result != null) return result;
    LOG.error("Invalid method call expression. Children:\n" + DebugUtil.psiTreeToString(expression, false));
    return result;
  }

  @NotNull
  public PsiType[] getTypeArguments() {
    return getMethodExpression().getTypeParameters();
  }

  @NotNull
  public PsiReferenceExpression getMethodExpression() {
    return (PsiReferenceExpression)findChildByRoleAsPsiElement(ChildRole.METHOD_EXPRESSION);
  }

  @NotNull
  public PsiExpressionList getArgumentList() {
    return (PsiExpressionList)findChildByRoleAsPsiElement(ChildRole.ARGUMENT_LIST);
  }

  public ASTNode findChildByRole(int role) {
    LOG.assertTrue(ChildRole.isUnique(role));
    switch (role) {
      default:
        return null;

      case ChildRole.METHOD_EXPRESSION:
        return getFirstChildNode();

      case ChildRole.ARGUMENT_LIST:
        return TreeUtil.findChild(this, JavaElementType.EXPRESSION_LIST);
    }
  }

  public int getChildRole(ASTNode child) {
    LOG.assertTrue(child.getTreeParent() == this);
    IElementType i = child.getElementType();
    if (i == JavaElementType.EXPRESSION_LIST) {
      return ChildRole.ARGUMENT_LIST;
    }
    else {
      if (ElementType.EXPRESSION_BIT_SET.contains(child.getElementType())) {
        return ChildRole.METHOD_EXPRESSION;
      }
      return ChildRoleBase.NONE;
    }
  }

  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof JavaElementVisitor) {
      ((JavaElementVisitor)visitor).visitMethodCallExpression(this);
    }
    else {
      visitor.visitElement(this);
    }
  }

  public String toString() {
    return "PsiMethodCallExpression:" + getText();
  }

  private static final TypeEvaluator ourTypeEvaluator = new TypeEvaluator();

  private static class TypeEvaluator implements Function<PsiExpression, PsiType> {
    public PsiType fun(final PsiExpression call) {
      PsiReferenceExpression methodExpression = ((PsiMethodCallExpression)call).getMethodExpression();
      final JavaResolveResult result = methodExpression.advancedResolve(false);
      final PsiMethod method = (PsiMethod)result.getElement();
      if (method == null) return null;
      PsiManager manager = call.getManager();

      final LanguageLevel languageLevel = PsiUtil.getLanguageLevel(call);
      boolean is15OrHigher = languageLevel.compareTo(LanguageLevel.JDK_1_5) >= 0;
      if (is15OrHigher) {
        //JLS3 15.8.2
        if (GET_CLASS_METHOD.equals(method.getName()) && "java.lang.Object".equals(method.getContainingClass().getQualifiedName())) {
          PsiExpression qualifier = methodExpression.getQualifierExpression();
          PsiType qualifierType = null;
          if (qualifier != null) {
            qualifierType = TypeConversionUtil.erasure(qualifier.getType());
          }
          else {
            ASTNode parent = call.getNode().getTreeParent();
            while (parent != null && parent.getElementType() != JavaElementType.CLASS) parent = parent.getTreeParent();
            if (parent != null) {
              qualifierType = JavaPsiFacade.getInstance(manager.getProject()).getElementFactory().createType((PsiClass)parent.getPsi());
            }
          }
          if (qualifierType != null) {
            PsiClass javaLangClass = JavaPsiFacade.getInstance(manager.getProject()).findClass("java.lang.Class", call.getResolveScope());
            if (javaLangClass != null && javaLangClass.getTypeParameters().length == 1) {
              Map<PsiTypeParameter, PsiType> map = new HashMap<PsiTypeParameter, PsiType>();
              map.put(javaLangClass.getTypeParameters()[0], PsiWildcardType.createExtends(manager, qualifierType));
              PsiSubstitutor substitutor = JavaPsiFacade.getInstance(manager.getProject()).getElementFactory().createSubstitutor(map);
              return JavaPsiFacade.getInstance(manager.getProject()).getElementFactory().createType(javaLangClass, substitutor, languageLevel);
            }
          }
        }
      }

      PsiType ret = method.getReturnType();
      if (ret == null) return null;
      if (ret instanceof PsiClassType) {
        ret = ((PsiClassType)ret).setLanguageLevel(languageLevel);
      }
      if (is15OrHigher) {
        final PsiSubstitutor substitutor = result.getSubstitutor();
        if (PsiUtil.isRawSubstitutor(method, substitutor)) return TypeConversionUtil.erasure(ret);
        PsiType substitutedReturnType = substitutor.substitute(ret);
        return PsiImplUtil.normalizeWildcardTypeByPosition(substitutedReturnType, call);
      }
      return TypeConversionUtil.erasure(ret);
    }
  }
}

