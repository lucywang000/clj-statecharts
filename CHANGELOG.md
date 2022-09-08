## v0.1.6 2022/9/8

- expose `clock/now` to actions fn to facilitate unit-testing delayed
  transitions. See commit [8541631](https://github.com/lucywang000/clj-statecharts/commit/8541631670dddb599091706e14849b4e6ed7377c)

## v0.1.5 2022/4/13

- the fsm schemas are now open instead of closed (#11)

## v0.1.4 2022/3/31

- include current state in the error message when reporting unknown event

## v0.1.3 2022/3/17

- Structure scheduler to interoperate with data stores #10 (thanks to @mainej)

## v0.1.2 2022/3/1

- support eventless transitions on initial states
- update malli to 0.8.3

## v0.1.1 2021/10/28

- update malli to 0.6.2 to fix
  [a compiler warning](https://github.com/metosin/malli/issues/536).

## v0.1.0 2021/6/21

- first public release.
