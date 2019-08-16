package testcases.T070_scope_factory_scope_extends_dependencies;

import motif.Scope;
import motif.ScopeFactory;

@Scope
public interface FooScope extends BarScope.Dependencies {

    String string();

    @motif.Objects
    class Objects {

        Integer integer() {
            return 1;
        }

        String string(FooScope scope) {
            return new BarScope.Factory().create(scope).string();
        }
    }

    interface Dependencies {}

    class Factory extends ScopeFactory<FooScope, Dependencies> {}
}
