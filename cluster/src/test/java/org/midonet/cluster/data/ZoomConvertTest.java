/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data;

import java.util.Random;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static org.midonet.cluster.models.TestModels.TestFlatMessage;
import static org.midonet.cluster.models.TestModels.TestFlatMessage.Enum.FIRST_VALUE;
import static org.midonet.cluster.models.TestModels.TestFlatMessage.Enum.SECOND_VALUE;
import static org.midonet.cluster.models.TestModels.TestFlatMessage.Enum.THIRD_VALUE;

/**
 * This tests the conversion between POJOs and Protobufs, when using
 * inheritance. The tested classes are the following, where the classes with (*)
 * are abstract classes (hence cannot be instantiated directly and require
 * a ZoomClass annotation specifying a factory class)
 *
 *  +-----------------+
 *  | AbstractBase(*) |
 *  +-----------------+
 *     |
 *  +--------------------+
 *  | AbstractDerived(*) |
 *  +--------------------+
 *     |
 *  +------+
 *  | Base |
 *  +------+
 *     |----------------|-----------------|
 *  +--------------+ +---------------+ +--------------+
 *  | FirstDerived | | SecondDerived | | ThirdDerived |
 *  +--------------+ +---------------+ +--------------+
 *     |
 *  +-----+
 *  | Top |
 *  +-----+
 *
 * The Protobufs message model is FLAT, meaning that the fields from all classes
 * are stored in the same Protobufs message. Each class below the AbstractBase
 * class provides a factory in the ZoomClass annotation, allowing the converter
 * to differentiate the corresponding POJO type for different Protobufs
 * messages.
 */
public class ZoomConvertTest {

    private static final Random random = new Random();

    /**
     * Conversion of a Base object to Protobufs and back to AbstractBase class
     * should fail, because the AbstractBase class does not have a factory,
     * specifying a non-abstract class.
     */
    @Test(expected = InstantiationException.class)
    public void testBaseClassToAbstractBaseClass() {
        Base obj = new Base();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        ZoomConvert.fromProto(proto, AbstractBase.class);
    }

    /**
     * Conversion of a Base object to Protobufs and back to AbstractDerived
     * class should succeed, because the AbstractDerived class has a factory.
     */
    @Test
    public void testBaseClassToAbstractDerivedClass() {
        Base obj = new Base();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        AbstractDerived abstractDerived =
            ZoomConvert.fromProto(proto, AbstractDerived.class);

        assertTrue(obj.equals(abstractDerived));
    }

    /**
     * Conversion of a Base object to Protobufs and back to Base class should
     * succeed.
     */
    @Test
    public void testBaseClassToBaseClass() {
        Base obj = new Base();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        Base base = ZoomConvert.fromProto(proto, Base.class);

        assertTrue(obj.equals(base));
    }

    /**
     * Conversion of a Base object to Protobufs and back to FirstDerived class
     * should convert null fields in the original POJO back to uninitialized
     * (null) fields.
     */
    @Test
    public void testBaseClassToFirstDerivedClass() {
        Base obj = new Base();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        // Unset fields will be ignored.
        ZoomConvert.fromProto(proto, FirstDerived.class);
    }

    /**
     * Conversion of a Base object to Protobufs and back to SecondDerived class
     * should convert null fields in the original POJO back to uninitialized
     * (null) fields.
     */
    @Test
    public void testBaseClassToSecondDerivedClass() {
        Base obj = new Base();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        // Unset fields will be ignored.
        ZoomConvert.fromProto(proto, SecondDerived.class);
    }

    /**
     * Conversion of a Base object to Protobufs and back to ThirdDerived class
     * should convert null fields in the original POJO back to uninitialized
     * (null) fields.
     */
    @Test
    public void testBaseClassToThirdDerivedClass() {
        Base obj = new Base();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        // Unset fields will be ignored.
        ZoomConvert.fromProto(proto, ThirdDerived.class);
    }

    /**
     * Conversion of a Base object to Protobufs and back to Top class should
     * should convert null fields in the original POJO back to uninitialized
     * (null) fields.
     */
    @Test
    public void testBaseClassToTopClass() {
        Base obj = new Base();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        // Unset fields will be ignored.
        ZoomConvert.fromProto(proto, Top.class);
    }

    /**
     * Conversion of a FirstDerived object to Protobufs and back to AbstractBase
     * class should fail, because the AbstractBase class does not have a
     * factory, specifying a non-abstract class.
     */
    @Test(expected = InstantiationException.class)
    public void testFirstDerivedClassToAbstractBaseClass() {
        FirstDerived obj = new FirstDerived();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        ZoomConvert.fromProto(proto, AbstractBase.class);
    }

    /**
     * Conversion of a FirstDerived object to Protobufs and back to
     * AbstractDerived class should succeed.
     */
    @Test
    public void testFirstDerivedClassToAbstractDerivedClass() {
        FirstDerived obj = new FirstDerived();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        AbstractDerived abstractDerived =
            ZoomConvert.fromProto(proto, AbstractDerived.class);

        assertTrue(obj.equals(abstractDerived));
    }

    /**
     * Conversion of a FirstDerived object to Protobufs and back to Base class
     * should succeed.
     */
    @Test
    public void testFirstDerivedClassToBaseClass() {
        FirstDerived obj = new FirstDerived();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        Base base = ZoomConvert.fromProto(proto, Base.class);

        assertTrue(obj.equals(base));
    }

    /**
     * Conversion of a FirstDerived object to Protobufs and back to FirstDerived
     * class should succeed.
     */
    @Test
    public void testFirstDerivedClassToFirstDerivedClass() {
        FirstDerived obj = new FirstDerived();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        FirstDerived firstDerived =
            ZoomConvert.fromProto(proto, FirstDerived.class);

        assertTrue(obj.equals(firstDerived));
    }

    /**
     * Conversion of a FirstDerived object to Protobufs and back to
     * SecondDerived class should convert null fields in the original POJO back
     * to uninitialized (null) fields.
     */
    @Test
    public void testFirstDerivedClassToSecondDerivedClass() {
        FirstDerived obj = new FirstDerived();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        // Unset fields will be just ignored.
        ZoomConvert.fromProto(proto, SecondDerived.class);
    }

    /**
     * Conversion of a FirstDerived object to Protobufs and back to
     * ThirdDerived class should convert null fields in the original POJO back
     * to uninitialized (null) fields.
     */
    @Test
    public void testFirstDerivedClassToThirdDerivedClass() {
        FirstDerived obj = new FirstDerived();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        // Unset fields will be ignored.
        ZoomConvert.fromProto(proto, ThirdDerived.class);
    }

    /**
     * Conversion of a FirstDerived object to Protobufs and back to Top class
     * should convert null fields in the original POJO back to uninitialized
     * (null) fields.
     */
    @Test
    public void testFirstDerivedClassToTopClass() {
        FirstDerived obj = new FirstDerived();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        // Unset fields will be ignored.
        ZoomConvert.fromProto(proto, Top.class);
    }

    /**
     * Conversion of a Top object to Protobufs and back to FirstDerived class
     * should succeed. However the FirstDerived object is not equal to the Top
     * object.
     */
    @Test
    public void testTopClassToFirstDerivedClass() {
        Top obj = new Top();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        FirstDerived firstDerived =
            ZoomConvert.fromProto(proto, FirstDerived.class);

        // This assertion should be false, because the converted object is not
        // a Top class instance.
        assertFalse(obj.equals(firstDerived));
    }

    /**
     * Conversion of a Top object to Protobufs and back to SecondDerived class
     * should convert null fields in the original POJO back to uninitialized
     * (null) fields.
     */
    @Test
    public void testTopClassToSecondDerivedClass() {
        Top obj = new Top();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        // Unset fields in proto will be ignored.
        ZoomConvert.fromProto(proto, SecondDerived.class);
    }

    /**
     * Conversion of a Top object to Protobufs and back to Top class should
     * succeed.
     */
    @Test
    public void testTopClassToTopClass() {
        Top obj = new Top();

        TestFlatMessage proto =
            ZoomConvert.toProto(obj, TestFlatMessage.class);

        assertTrue(obj.compare(proto));

        Top top = ZoomConvert.fromProto(proto, Top.class);

        assertTrue(obj.equals(top));
    }

    public static abstract class AbstractBase extends ZoomObject {
        @ZoomField(name = "abstract_base_int")
        protected int abstractBaseInt = random.nextInt();

        public boolean compare(TestFlatMessage message) {
            return message.hasAbstractBaseInt() &&
                   message.getAbstractBaseInt() == abstractBaseInt;
        }

        @Override
        public boolean equals(Object obj) {
            if (null == obj || !(obj instanceof AbstractBase)) return false;
            AbstractBase o = (AbstractBase)obj;
            return o.abstractBaseInt == abstractBaseInt;
        }
    }

    @ZoomClass(clazz = TestFlatMessage.class,
               factory = AbstractDerivedFactory.class)
    public static abstract class AbstractDerived extends AbstractBase {
        @ZoomField(name = "abstract_derived_int")
        protected int abstractDerivedInt = random.nextInt();

        @Override
        public boolean compare(TestFlatMessage message) {
            return super.compare(message) &&
                   message.hasAbstractDerivedInt() &&
                   message.getAbstractDerivedInt() == abstractDerivedInt;
        }

        @Override
        public boolean equals(Object obj) {
            if (null == obj || !(obj instanceof AbstractDerived)) return false;
            AbstractDerived o = (AbstractDerived)obj;
            return super.equals(obj) &&
                   o.abstractDerivedInt == abstractDerivedInt;
        }
    }

    @ZoomClass(clazz = TestFlatMessage.class,
               factory = BaseFactory.class)
    public static class Base extends AbstractDerived {
        @ZoomEnum(clazz = TestFlatMessage.Enum.class)
        enum Enum {
            @ZoomEnumValue(value = "NONE")
            NONE,
            @ZoomEnumValue(value = "FIRST")
            FIRST,
            @ZoomEnumValue(value = "SECOND")
            SECOND,
            @ZoomEnumValue(value = "THIRD")
            THIRD
        }

        @ZoomField(name = "base_int")
        protected int baseInt = random.nextInt();
        @ZoomField(name = "base_enum")
        protected Enum baseEnum = Enum.NONE;

        @Override
        public boolean compare(TestFlatMessage message) {
            return super.compare(message) &&
                   message.hasBaseInt() &&
                   message.hasBaseEnum() &&
                   message.getBaseInt() == baseInt &&
                   message.getBaseEnum().toString().equals(baseEnum.toString());
        }

        @Override
        public boolean equals(Object obj) {
            if (null == obj || !(obj instanceof Base)) return false;
            Base o = (Base)obj;
            return super.equals(obj) &&
                   o.baseInt == baseInt &&
                   o.baseEnum == baseEnum;
        }
    }

    @ZoomClass(clazz = TestFlatMessage.class,
               factory = FirstDerivedFactory.class)
    public static class FirstDerived extends Base {
        @ZoomField(name = "first_derived_int")
        protected int firstDerivedInt = random.nextInt();
        @ZoomField(name = "is_top")
        protected boolean isTop = false;

        public FirstDerived() {
            super.baseEnum = Enum.FIRST;
        }

        @Override
        public boolean compare(TestFlatMessage message) {
            return super.compare(message) &&
                   message.hasFirstDerivedInt() &&
                   message.hasIsTop() &&
                   message.getFirstDerivedInt() == firstDerivedInt &&
                   message.getIsTop() == isTop;
        }

        @Override
        public boolean equals(Object obj) {
            if (null == obj || !(obj instanceof FirstDerived)) return false;
            FirstDerived o = (FirstDerived)obj;
            return super.equals(obj) &&
                   o.firstDerivedInt == firstDerivedInt &&
                   o.isTop == isTop;
        }
    }

    public static class SecondDerived extends Base {
        @ZoomField(name = "second_derived_int")
        protected int secondDerivedInt = random.nextInt();

        public SecondDerived() {
            super.baseEnum = Enum.SECOND;
        }

        @Override
        public boolean compare(TestFlatMessage message) {
            return super.compare(message) &&
                   message.hasSecondDerivedInt() &&
                   message.getSecondDerivedInt() == secondDerivedInt;
        }

        @Override
        public boolean equals(Object obj) {
            if (null == obj || !(obj instanceof SecondDerived)) return false;
            SecondDerived o = (SecondDerived)obj;
            return super.equals(obj) &&
                   o.secondDerivedInt == secondDerivedInt;
        }
    }

    public static class ThirdDerived extends Base {
        @ZoomField(name = "third_derived_int")
        protected int thirdDerivedInt = random.nextInt();

        public ThirdDerived() {
            super.baseEnum = Enum.THIRD;
        }

        @Override
        public boolean compare(TestFlatMessage message) {
            return super.compare(message) &&
                   message.hasThirdDerivedInt() &&
                   message.getThirdDerivedInt() == thirdDerivedInt;
        }

        @Override
        public boolean equals(Object obj) {
            if (null == obj || !(obj instanceof ThirdDerived)) return false;
            ThirdDerived o = (ThirdDerived)obj;
            return super.equals(obj) &&
                   o.thirdDerivedInt == thirdDerivedInt;
        }
    }

    public static class Top extends FirstDerived {
        @ZoomField(name = "top_int")
        protected int topInt = random.nextInt();

        @Override
        public boolean compare(TestFlatMessage message) {
            return super.compare(message) &&
                   message.hasTopInt() &&
                   message.getTopInt() == topInt;
        }

        @Override
        public boolean equals(Object obj) {
            if (null == obj || !(obj instanceof Top)) return false;
            Top o = (Top)obj;
            return super.equals(obj) &&
                   o.topInt == topInt;
        }
    }

    public static class AbstractDerivedFactory
        implements ZoomConvert.Factory<AbstractDerived, TestFlatMessage> {
        @Override
        public Class<? extends AbstractDerived> getType(TestFlatMessage proto) {
            return Base.class;
        }
    }

    public static class BaseFactory
        implements ZoomConvert.Factory<Base, TestFlatMessage> {
        @Override
        public Class<? extends Base> getType(TestFlatMessage proto) {
            switch (proto.getBaseEnum().getNumber()) {
                case FIRST_VALUE: return FirstDerived.class;
                case SECOND_VALUE: return SecondDerived.class;
                case THIRD_VALUE: return ThirdDerived.class;
                default: return Base.class;
            }
        }
    }

    public static class FirstDerivedFactory
        implements ZoomConvert.Factory<FirstDerived, TestFlatMessage> {
        @Override
        public Class<? extends FirstDerived> getType(
            TestFlatMessage proto) {
            return proto.getIsTop() ? Top.class : FirstDerived.class;
        }
    }
}
